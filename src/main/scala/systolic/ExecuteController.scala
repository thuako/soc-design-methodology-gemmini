package systolic

import chisel3._
import chisel3.util._
import SystolicISA._
import Util._
import freechips.rocketchip.config.Parameters

// TODO handle reads from the same bank
// TODO handle reads from addresses that haven't been written yet
class ExecuteController[T <: Data](xLen: Int, config: SystolicArrayConfig, spaddr: SPAddr,
                                               inputType: T, outputType: T, accType: T)
                                              (implicit p: Parameters, ev: Arithmetic[T]) extends Module {
  import config._
  import ev._

  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new SystolicCmdWithDeps))

    val read  = Vec(sp_banks, new ScratchpadReadIO(sp_bank_entries, sp_width))
    val write = Vec(sp_banks, new ScratchpadWriteIO(sp_bank_entries, sp_width))

    val acc_read = new AccumulatorReadIO(acc_rows, Vec(meshColumns, Vec(tileColumns, accType)))
    val acc_write = new AccumulatorWriteIO(acc_rows, Vec(meshColumns, Vec(tileColumns, accType)))

    // TODO what's a better way to express no bits?
    val pushLoad = Decoupled(UInt(1.W))
    val pullLoad = Flipped(Decoupled(UInt(1.W)))
    val pushStore = Decoupled(UInt(1.W))
    val pullStore = Flipped(Decoupled(UInt(1.W)))

    val pushLoadLeft = Input(UInt(log2Ceil(depq_len+1).W))
    val pushStoreLeft = Input(UInt(log2Ceil(depq_len+1).W))
  })

  val block_size = meshRows*tileRows
  assert(ex_queue_length >= 6)

  val tagWidth = 32
  val tag_garbage = Cat(Seq.fill(tagWidth)(1.U(1.W)))
  val tag_with_deps = new Bundle {
    val pushLoad = Bool()
    val pushStore = Bool()
    val tag = UInt(tagWidth.W)
  }

  val acc_addr = new Bundle {
    val junk = UInt((xLen - tagWidth - log2Ceil(acc_rows)).W)
    val is_acc_addr = Bool()
    val acc = Bool()
    val junk2 = UInt((tagWidth - log2Ceil(acc_rows)-2).W)
    val row = UInt(log2Ceil(acc_rows).W)
  }

  val cmd_q_heads = 2
  val (cmd, _) = MultiHeadedQueue(io.cmd, ex_queue_length, cmd_q_heads)
  cmd.pop := 0.U

  val current_dataflow = RegInit(Dataflow.OS.id.U)

  val functs = cmd.bits.map(_.cmd.inst.funct)
  val rs1s = VecInit(cmd.bits.map(_.cmd.rs1))
  val rs2s = VecInit(cmd.bits.map(_.cmd.rs2))

  val DoSetMode = functs(0) === MODE_CMD
  val DoComputes = functs.map(f => f === COMPUTE_AND_FLIP_CMD || f === COMPUTE_AND_STAY_CMD)
  val DoPreloads = functs.map(_ === PRELOAD_CMD)

  val preload_cmd_place = Mux(DoPreloads(0), 0.U, 1.U)

  val in_s = functs(0) === COMPUTE_AND_FLIP_CMD
  val in_s_flush = Reg(Bool())
  when (current_dataflow === Dataflow.WS.id.U) {
    in_s_flush := 0.U
  }

  val in_shift = RegInit(0.U((accType.getWidth - outputType.getWidth).W))

  // SRAM addresses of matmul operands
  val a_address_rs1 = WireInit(rs1s(0).asTypeOf(spaddr))
  val b_address_rs2 = WireInit(rs2s(0).asTypeOf(spaddr))
  val d_address_rs1 = WireInit(rs1s(preload_cmd_place).asTypeOf(spaddr))
  val c_address_rs2 = WireInit(rs2s(preload_cmd_place).asTypeOf(spaddr))

  val preload_zeros = WireInit(d_address_rs1.asUInt()(tagWidth-1, 0) === tag_garbage)
  val accumulate_zeros = WireInit(b_address_rs2.asUInt()(tagWidth-1, 0) === tag_garbage)

  // Dependency stuff
  val pushLoads = cmd.bits.map(_.deps.pushLoad)
  val pullLoads = cmd.bits.map(_.deps.pullLoad)
  val pushStores = cmd.bits.map(_.deps.pushStore)
  val pullStores = cmd.bits.map(_.deps.pullStore)
  val pushDeps = (pushLoads zip pushStores).map { case (pl, ps) => pl || ps }
  val pullDeps = (pullLoads zip pullStores).map { case (pl, ps) => pl || ps }

  val pull_deps_ready = (pullDeps, pullLoads, pullStores).zipped.map { case (pullDep, pullLoad, pullStore) =>
    !pullDep || (pullLoad && !pullStore && io.pullLoad.valid) ||
      (pullStore && !pullLoad && io.pullStore.valid) ||
      (pullLoad && pullStore && io.pullLoad.valid && io.pullStore.valid)
  }

  val push_deps_ready = (pushDeps, pushLoads, pushStores).zipped.map { case (pushDep, pushLoad, pushStore) =>
    !pushDep || (pushLoad && !pushStore && io.pushLoadLeft >= 3.U) ||
      (pushStore && !pushLoad && io.pushStoreLeft >= 3.U) ||
      (pushStore && pushLoad && io.pushLoadLeft >= 3.U && io.pushStoreLeft >= 3.U)
  }

  io.pushLoad.valid := false.B
  io.pushLoad.bits := DontCare
  io.pushStore.valid := false.B
  io.pushStore.bits := DontCare

  io.pullLoad.ready := false.B
  io.pullStore.ready := false.B

  // Instantiate the actual mesh
  val mesh = Module(new MeshWithMemory(inputType, outputType, accType, tag_with_deps, dataflow, tileRows,
    tileColumns, meshRows, meshColumns, shifter_banks, shifter_banks))

  mesh.io.a.valid := false.B
  mesh.io.b.valid := false.B
  mesh.io.d.valid := false.B
  mesh.io.tag_in.valid := false.B
  mesh.io.tag_garbage.pushLoad := false.B
  mesh.io.tag_garbage.pushStore := false.B
  mesh.io.tag_garbage.tag := tag_garbage
  mesh.io.flush.valid := false.B

  mesh.io.a.bits := DontCare
  mesh.io.b.bits := DontCare
  mesh.io.d.bits := DontCare
  mesh.io.tag_in.bits := DontCare
  mesh.io.s := in_s
  mesh.io.m := current_dataflow
  mesh.io.shift := in_shift

  // STATE defines
  val waiting_for_cmd :: compute :: flush :: flushing :: Nil = Enum(4)
  val control_state = RegInit(waiting_for_cmd)

  // SRAM scratchpad
  val a_read_bank_number = a_address_rs1.bank
  val b_read_bank_number = b_address_rs2.bank
  val d_read_bank_number = d_address_rs1.bank
  
  val a_read_from_acc = a_address_rs1.asTypeOf(acc_addr).is_acc_addr
  val b_read_from_acc = b_address_rs2.asTypeOf(acc_addr).is_acc_addr
  val d_read_from_acc = d_address_rs1.asTypeOf(acc_addr).is_acc_addr

  val start_inputting_ab = WireInit(false.B)
  val start_inputting_d = WireInit(false.B)
  val start_array_outputting = WireInit(false.B)

  val perform_single_preload = RegInit(false.B)
  val perform_single_mul = RegInit(false.B)
  val perform_mul_pre = RegInit(false.B)

  val output_counter = new Counter(block_size)
  val fire_counter = Reg(UInt((log2Ceil(block_size) max 1).W))
  val fire_count_started = RegInit(false.B)
  val fired_all_rows = fire_counter === 0.U && fire_count_started
  val about_to_fire_all_rows = fire_counter === (block_size-1).U && mesh.io.a.ready && mesh.io.b.ready && mesh.io.d.ready // TODO change when square requirement lifted

  fire_counter := 0.U
  when (mesh.io.a.ready && mesh.io.b.ready && mesh.io.d.ready &&
    (start_inputting_ab || start_inputting_d)) {
    fire_counter := wrappingAdd(fire_counter, 1.U, block_size)
  }

  // Scratchpad reads
  for(i <- 0 until sp_banks){
    val read_a = !a_read_from_acc && a_read_bank_number === i.U && start_inputting_ab
    val read_b = !b_read_from_acc && b_read_bank_number === i.U && start_inputting_ab && !accumulate_zeros
    val read_d = !d_read_from_acc && d_read_bank_number === i.U && start_inputting_d && !preload_zeros

    io.read(i).en := read_a || read_b || read_d
    io.read(i).addr := MuxCase(a_address_rs1.row + fire_counter,
      Seq(read_b -> (b_address_rs2.row + fire_counter),
        read_d -> (d_address_rs1.row + block_size.U - 1.U - fire_counter))
    )
  }

  // Accumulator read // TODO can only handle one acc read for now
  {
    val read_a_from_acc = a_read_from_acc && start_inputting_ab
    val read_b_from_acc = b_read_from_acc && start_inputting_ab && !accumulate_zeros
    val read_d_from_acc = d_read_from_acc && start_inputting_d && !preload_zeros

    io.acc_read.en := read_a_from_acc || read_b_from_acc || read_d_from_acc

    io.acc_read.addr := MuxCase(a_address_rs1.asTypeOf(acc_addr).row + fire_counter,
      Seq(read_b_from_acc -> (b_address_rs2.asTypeOf(acc_addr).row + fire_counter),
        read_d_from_acc -> (d_address_rs1.asTypeOf(acc_addr).row + block_size.U - 1.U - fire_counter)))
  }

  val readData = VecInit(io.read.map(_.data))
  val accReadData = VecInit(io.acc_read.data.map(v => VecInit(v.map(e => (e >> in_shift).relu.clippedToWidthOf(inputType))))).asUInt // TODO make relu optional
  
  val dataAbank = WireInit(a_read_bank_number)
  val dataBbank = WireInit(b_read_bank_number)
  val dataDbank = WireInit(d_read_bank_number)

  val dataA = Mux(RegNext(a_read_from_acc), accReadData, readData(RegNext(dataAbank)))
  val dataB = MuxCase(readData(RegNext(dataBbank)),
    Seq(RegNext(accumulate_zeros) -> 0.U, RegNext(b_read_from_acc) -> accReadData))
  val dataD = MuxCase(readData(RegNext(dataDbank)),
    Seq(RegNext(preload_zeros) -> 0.U, RegNext(d_read_from_acc) -> accReadData))

  // FSM logic
  switch (control_state) {
    is (waiting_for_cmd) {
      // Default state
      perform_single_preload := false.B
      perform_mul_pre := false.B
      perform_single_mul := false.B
      fire_count_started := false.B

      when(cmd.valid(0) && pull_deps_ready(0) && push_deps_ready(0))
      {
        when(DoSetMode) {
          val data_mode = rs1s(0)(0)
          current_dataflow := data_mode
          in_shift := rs2s(0)

          io.pullLoad.ready := cmd.bits(0).deps.pullLoad
          io.pullStore.ready := cmd.bits(0).deps.pullStore
          // TODO add support for pushing dependencies on the set-mode command
          assert(!pushDeps(0), "pushing depenencies on setmode not supported")

          cmd.pop := 1.U
        }

        // Preload
        .elsewhen(DoPreloads(0)) {
          perform_single_preload := true.B
          start_inputting_d := true.B

          io.pullLoad.ready := cmd.bits(0).deps.pullLoad
          io.pullStore.ready := cmd.bits(0).deps.pullStore

          when (current_dataflow === Dataflow.OS.id.U) {
            in_s_flush := rs2s(0)(tagWidth-1, 0) =/= tag_garbage
          }

          fire_count_started := true.B
          control_state := compute
        }

        // Overlap compute and preload
        .elsewhen(DoComputes(0) && cmd.valid(1) && DoPreloads(1) && pull_deps_ready(1) && push_deps_ready(1)) {
          perform_mul_pre := true.B
          start_inputting_ab := true.B
          start_inputting_d := true.B

          io.pullLoad.ready := cmd.bits(1).deps.pullLoad
          io.pullStore.ready := cmd.bits(1).deps.pullStore

          when (current_dataflow === Dataflow.OS.id.U) {
            in_s_flush := rs2s(1)(tagWidth - 1, 0) =/= tag_garbage
          }

          fire_count_started := true.B
          control_state := compute
        }

        // Single mul
        .elsewhen(DoComputes(0)) {
          perform_single_mul := true.B
          start_inputting_ab := true.B

          fire_count_started := true.B
          control_state := compute
        }
      }
    }
    is (compute) {
      // Only preloading
      when(perform_single_preload) {
        start_inputting_d := true.B

        when (about_to_fire_all_rows) {
          cmd.pop := 1.U
          control_state := waiting_for_cmd
        }
      }

      // Overlapping
      .elsewhen(perform_mul_pre)
      {
        start_inputting_ab := true.B
        start_inputting_d := true.B

        when (about_to_fire_all_rows) {
          cmd.pop := 2.U
          control_state := waiting_for_cmd
        }
      }

      // Only compute
      .elsewhen(perform_single_mul) {
        start_inputting_ab := true.B

        // TODO do we waste a cycle here?
        when(fired_all_rows) {
          perform_single_mul := false.B

          start_inputting_ab := false.B

          fire_count_started := false.B
          cmd.pop := 1.U
          // TODO don't go straight to flush if avoidable
          control_state := flush
        }
      }
    }
    is (flush) {
      when(mesh.io.flush.ready) {
        mesh.io.flush.valid := true.B
        mesh.io.s := in_s_flush
        control_state := flushing
      }
    }
    is (flushing) {
      when(mesh.io.flush.ready) {
        control_state := waiting_for_cmd
      }
    }
  }

  // Computing logic
  when (perform_mul_pre || perform_single_mul || perform_single_preload) {
    // Default inputs
    mesh.io.a.valid := true.B
    mesh.io.b.valid := true.B
    mesh.io.d.valid := true.B
    mesh.io.tag_in.valid := true.B

    mesh.io.a.bits := dataA.asTypeOf(Vec(meshRows, Vec(tileRows, inputType)))
    mesh.io.b.bits := dataB.asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))
    mesh.io.d.bits := dataD.asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))

    mesh.io.tag_in.bits.pushLoad := VecInit(pushLoads take 2)(preload_cmd_place)
    mesh.io.tag_in.bits.pushStore := VecInit(pushStores take 2)(preload_cmd_place)
    mesh.io.tag_in.bits.tag := c_address_rs2.asUInt()
  }

  when (perform_single_preload) {
    mesh.io.a.bits := (0.U).asTypeOf(Vec(meshRows, Vec(tileRows, inputType)))
    mesh.io.b.bits := (0.U).asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))
  }

  when (perform_single_mul) {
    mesh.io.tag_in.bits.pushLoad := false.B
    mesh.io.tag_in.bits.pushStore := false.B
    mesh.io.tag_in.bits.tag := tag_garbage
  }

  // Scratchpad writes
  val w_address = mesh.io.tag_out.tag.asTypeOf(spaddr)
  val w_address_acc = mesh.io.tag_out.tag.asTypeOf(acc_addr)

  val write_to_acc = w_address_acc.is_acc_addr

  val w_bank = w_address.bank
  val w_row = Mux(write_to_acc, w_address_acc.row, w_address.row)

  val current_w_bank_address = Mux(current_dataflow === Dataflow.WS.id.U, w_row + output_counter.value,
    w_row + block_size.U - 1.U - output_counter.value)

  val is_garbage_addr = mesh.io.tag_out.tag === tag_garbage

  // Write to normal scratchpad
  for(i <- 0 until sp_banks) {
    // TODO make relu optional
    val activated_wdata = VecInit(mesh.io.out.bits.map(v => VecInit(v.map(_.relu.clippedToWidthOf(inputType)))))

    io.write(i).en := start_array_outputting && w_bank === i.U && !write_to_acc && !is_garbage_addr
    io.write(i).addr := current_w_bank_address
    io.write(i).data := activated_wdata.asUInt()
  }

  // Write to accumulator
  {
    io.acc_write.en := start_array_outputting && write_to_acc && !is_garbage_addr
    io.acc_write.addr := current_w_bank_address
    io.acc_write.data := mesh.io.out.bits
    io.acc_write.acc := w_address_acc.acc
  }

  when(mesh.io.out.fire() && !is_garbage_addr) {
    when(output_counter.inc()) {
      io.pushLoad.valid := mesh.io.tag_out.pushLoad
      io.pushStore.valid := mesh.io.tag_out.pushStore

      assert(!mesh.io.tag_out.pushLoad || io.pushLoad.ready)
      assert(!mesh.io.tag_out.pushStore || io.pushStore.ready)
    }
    start_array_outputting := true.B
  }
}
