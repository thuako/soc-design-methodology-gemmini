package gemmini

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink.{TLIdentityNode, TLXbar}

import midas.targetutils.PerfCounter
import midas.targetutils.SynthesizePrintf
import Util._

class ScratchpadMemReadRequest[U <: Data](local_addr_t: LocalAddr, scale_t_bits: Int)
                              (implicit p: Parameters) extends CoreBundle {
  val vaddr = UInt(coreMaxAddrBits.W)
  val laddr = local_addr_t.cloneType

  val cols = UInt(16.W) // TODO don't use a magic number for the width here
  val repeats = UInt(16.W) // TODO don't use a magic number for the width here
  val scale = UInt(scale_t_bits.W)
  val has_acc_bitwidth = Bool()
  val all_zeros = Bool()
  val block_stride = UInt(16.W) // TODO magic numbers
  val cmd_id = UInt(8.W) // TODO don't use a magic number here
  val status = new MStatus

  override def cloneType: this.type = new ScratchpadMemReadRequest(local_addr_t, scale_t_bits).asInstanceOf[this.type]
}

class ScratchpadMemWriteRequest(local_addr_t: LocalAddr)
                              (implicit p: Parameters) extends CoreBundle {
  val vaddr = UInt(coreMaxAddrBits.W)
  val laddr = local_addr_t.cloneType

  val len = UInt(16.W) // TODO don't use a magic number for the width here
  val block = UInt(8.W) // TODO don't use a magic number for the width here

  val cmd_id = UInt(8.W) // TODO don't use a magic number here
  val status = new MStatus

  // Pooling variables
  val pool_en = Bool()
  val store_en = Bool()

  override def cloneType: this.type = new ScratchpadMemWriteRequest(local_addr_t).asInstanceOf[this.type]
}

class ScratchpadMemWriteResponse extends Bundle {
  val cmd_id = UInt(8.W) // TODO don't use a magic number here
}

class ScratchpadMemReadResponse extends Bundle {
  val bytesRead = UInt(16.W) // TODO magic number here
  val cmd_id = UInt(8.W) // TODO don't use a magic number here
}

class ScratchpadReadMemIO[U <: Data](local_addr_t: LocalAddr, scale_t_bits: Int)
                         (implicit p: Parameters) extends CoreBundle {
  val req = Decoupled(new ScratchpadMemReadRequest(local_addr_t, scale_t_bits))
  val resp = Flipped(Valid(new ScratchpadMemReadResponse))

  override def cloneType: this.type = new ScratchpadReadMemIO(local_addr_t, scale_t_bits).asInstanceOf[this.type]
}

// class ScratchpadWriteMemIO(val nBanks: Int, val nRows: Int, val acc_rows: Int)
class ScratchpadWriteMemIO(local_addr_t: LocalAddr)
                         (implicit p: Parameters) extends CoreBundle {
  val req = Decoupled(new ScratchpadMemWriteRequest(local_addr_t))
  val resp = Flipped(Valid(new ScratchpadMemWriteResponse))

  override def cloneType: this.type = new ScratchpadWriteMemIO(local_addr_t).asInstanceOf[this.type]
}

class ScratchpadReadReq(val n: Int) extends Bundle {
  val addr = UInt(log2Ceil(n).W)
  val fromDMA = Bool()
}

class ScratchpadReadResp(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val fromDMA = Bool()
}

class ScratchpadReadIO(val n: Int, val w: Int) extends Bundle {
  val req = Decoupled(new ScratchpadReadReq(n))
  val resp = Flipped(Decoupled(new ScratchpadReadResp(w)))
}

class ScratchpadWriteIO(val n: Int, val w: Int, val mask_len: Int) extends Bundle {
  val en = Output(Bool())
  val addr = Output(UInt(log2Ceil(n).W))
  val mask = Output(Vec(mask_len, Bool()))
  val data = Output(UInt(w.W))
}

class ScratchpadBank(n: Int, w: Int, aligned_to: Int, single_ported: Boolean) extends Module {
  // This is essentially a pipelined SRAM with the ability to stall pipeline stages

  require(w % aligned_to == 0 || w < aligned_to)
  val mask_len = (w / (aligned_to * 8)) max 1 // How many mask bits are there?
  val mask_elem = UInt((w min (aligned_to * 8)).W) // What datatype does each mask bit correspond to?

  val io = IO(new Bundle {
    val read = Flipped(new ScratchpadReadIO(n, w))
    val write = Flipped(new ScratchpadWriteIO(n, w, mask_len))
  })

  val mem = SyncReadMem(n, Vec(mask_len, mask_elem))

  // When the scratchpad is single-ported, the writes take precedence
  val singleport_busy_with_write = single_ported.B && io.write.en

  when (io.write.en) {
    if (aligned_to >= w)
      mem.write(io.write.addr, io.write.data.asTypeOf(Vec(mask_len, mask_elem)))
    else
      mem.write(io.write.addr, io.write.data.asTypeOf(Vec(mask_len, mask_elem)), io.write.mask)
  }

  val raddr = io.read.req.bits.addr
  val ren = io.read.req.fire()
  val rdata = if (single_ported) {
    assert(!(ren && io.write.en))
    mem.read(raddr, ren && !io.write.en).asUInt()
  } else {
    mem.read(raddr, ren).asUInt()
  }

  val fromDMA = io.read.req.bits.fromDMA

  // Make a queue which buffers the result of an SRAM read if it can't immediately be consumed
  val q = Module(new Queue(new ScratchpadReadResp(w), 1, true, true))
  q.io.enq.valid := RegNext(ren)
  q.io.enq.bits.data := rdata
  q.io.enq.bits.fromDMA := RegNext(fromDMA)

  val q_will_be_empty = (q.io.count +& q.io.enq.fire()) - q.io.deq.fire() === 0.U
  io.read.req.ready := q_will_be_empty && !singleport_busy_with_write

  io.read.resp <> q.io.deq
}

class Scratchpad[T <: Data, U <: Data, V <: Data](config: GemminiArrayConfig[T, U, V])
    (implicit p: Parameters, ev: Arithmetic[T]) extends LazyModule {

  import config._
  import ev._

  val maxBytes = dma_maxbytes
  val dataBits = dma_buswidth

  val block_rows = meshRows * tileRows
  val block_cols = meshColumns * tileColumns
  val spad_w = inputType.getWidth *  block_cols
  val acc_w = accType.getWidth * block_cols

  val id_node = TLIdentityNode()
  val xbar_node = TLXbar()

  val reader = LazyModule(new StreamReader(config, max_in_flight_reqs, dataBits, maxBytes, spad_w, acc_w, aligned_to,
    sp_banks * sp_bank_entries, acc_banks * acc_bank_entries, block_rows, use_tlb_register_filter))
  val writer = LazyModule(new StreamWriter(max_in_flight_reqs, dataBits, maxBytes,
    if (acc_read_full_width) acc_w else spad_w, aligned_to, inputType, block_cols, use_tlb_register_filter))

  // TODO make a cross-bar vs two separate ports a config option
  // id_node :=* reader.node
  // id_node :=* writer.node

  xbar_node := reader.node // TODO
  xbar_node := writer.node
  id_node := xbar_node

  lazy val module = new LazyModuleImp(this) with HasCoreParameters {

    val io = IO(new Bundle {
      // DMA ports
      val dma = new Bundle {
        val read = Flipped(new ScratchpadReadMemIO(local_addr_t, mvin_scale_t_bits))
        val write = Flipped(new ScratchpadWriteMemIO(local_addr_t))
      }

      // SRAM ports
      val srams = new Bundle {
        val read = Flipped(Vec(sp_banks, new ScratchpadReadIO(sp_bank_entries, spad_w)))
        val write = Flipped(Vec(sp_banks, new ScratchpadWriteIO(sp_bank_entries, spad_w, (spad_w / (aligned_to * 8)) max 1)))
      }

      // Accumulator ports
      val acc = new Bundle {
        val read_req = Flipped(Vec(acc_banks, Decoupled(new AccumulatorReadReq(
          acc_bank_entries, log2Up(accType.getWidth), acc_scale_args.multiplicand_t
        ))))
        val read_resp = Vec(acc_banks, Decoupled(new AccumulatorScaleResp(
          Vec(meshColumns, Vec(tileColumns, inputType)),
          Vec(meshColumns, Vec(tileColumns, accType))
        )))
        val write = Flipped(Vec(acc_banks, Decoupled(new AccumulatorWriteReq(
          acc_bank_entries, Vec(meshColumns, Vec(tileColumns, accType))
        ))))
      }

      // TLB ports
      val tlb = Vec(2, new FrontendTLBIO)

      // Misc. ports
      val busy = Output(Bool())
      val flush = Input(Bool())
    })

    val write_dispatch_q = Queue(io.dma.write.req)
    write_dispatch_q.ready := false.B
    // Write scale queue is necessary to maintain in-order requests to accumulator scale unit
    // Writes from main SPAD just flow directly between scale_q and issue_q, while writes
    // From acc are ordered
    val write_scale_q = Module(new Queue(new ScratchpadMemWriteRequest(local_addr_t), mem_pipeline))
    val write_issue_q = Module(new Queue(new ScratchpadMemWriteRequest(local_addr_t), mem_pipeline+1, pipe=true))
    val read_issue_q = Module(new Queue(new ScratchpadMemReadRequest(local_addr_t, mvin_scale_t_bits), mem_pipeline+1, pipe=true)) // TODO can't this just be a normal queue?

    write_scale_q.io.enq.valid := false.B
    write_scale_q.io.enq.bits  := write_dispatch_q.bits
    write_scale_q.io.deq.ready := false.B

    write_issue_q.io.enq.valid := false.B
    write_issue_q.io.enq.bits := write_scale_q.io.deq.bits


    // Garbage can immediately fire between dispatch_q and scale_q
    when (write_dispatch_q.bits.laddr.is_garbage()) {
      write_scale_q.io.enq <> write_dispatch_q
    }
    // Non-acc or garbage can immediately fire between scale_q and issue_q
    when (write_scale_q.io.deq.bits.laddr.is_garbage() || !write_scale_q.io.deq.bits.laddr.is_acc_addr) {
      write_issue_q.io.enq <> write_scale_q.io.deq
    }


    val writeData = Wire(Valid(UInt((spad_w max acc_w).W)))
    writeData.valid := write_issue_q.io.deq.bits.laddr.is_garbage()
    writeData.bits := DontCare
    val fullAccWriteData = Wire(UInt(acc_w.W))
    fullAccWriteData := DontCare
    val writeData_is_full_width = !write_issue_q.io.deq.bits.laddr.is_garbage() &&
      write_issue_q.io.deq.bits.laddr.is_acc_addr && write_issue_q.io.deq.bits.laddr.read_full_acc_row
    val writeData_is_all_zeros = write_issue_q.io.deq.bits.laddr.is_garbage()

    writer.module.io.req.valid := write_issue_q.io.deq.valid && writeData.valid
    write_issue_q.io.deq.ready := writer.module.io.req.ready && writeData.valid
    writer.module.io.req.bits.vaddr := write_issue_q.io.deq.bits.vaddr
    writer.module.io.req.bits.len := Mux(writeData_is_full_width,
      write_issue_q.io.deq.bits.len * (accType.getWidth / 8).U,
      write_issue_q.io.deq.bits.len * (inputType.getWidth / 8).U)
    writer.module.io.req.bits.data := MuxCase(writeData.bits, Seq(
       writeData_is_all_zeros -> 0.U,
       writeData_is_full_width -> fullAccWriteData
    ))
    writer.module.io.req.bits.block := write_issue_q.io.deq.bits.block
    writer.module.io.req.bits.status := write_issue_q.io.deq.bits.status
    writer.module.io.req.bits.pool_en := write_issue_q.io.deq.bits.pool_en
    writer.module.io.req.bits.store_en := write_issue_q.io.deq.bits.store_en

    io.dma.write.resp.valid := false.B
    io.dma.write.resp.bits.cmd_id := write_dispatch_q.bits.cmd_id
    when (write_dispatch_q.bits.laddr.is_garbage() && write_dispatch_q.fire()) {
      io.dma.write.resp.valid := true.B
    }

    read_issue_q.io.enq <> io.dma.read.req

    val zero_writer = Module(new ZeroWriter(config, new ScratchpadMemReadRequest(local_addr_t, mvin_scale_t_bits)))

    when (io.dma.read.req.bits.all_zeros) {
      read_issue_q.io.enq.valid := false.B
      io.dma.read.req.ready := zero_writer.io.req.ready
    }

    zero_writer.io.req.valid := io.dma.read.req.valid && io.dma.read.req.bits.all_zeros
    zero_writer.io.req.bits.laddr := io.dma.read.req.bits.laddr
    zero_writer.io.req.bits.cols := io.dma.read.req.bits.cols
    zero_writer.io.req.bits.block_stride := io.dma.read.req.bits.block_stride
    zero_writer.io.req.bits.tag := io.dma.read.req.bits

    zero_writer.io.resp.ready := false.B

    reader.module.io.req.valid := read_issue_q.io.deq.valid
    read_issue_q.io.deq.ready := reader.module.io.req.ready
    reader.module.io.req.bits.vaddr := read_issue_q.io.deq.bits.vaddr
    reader.module.io.req.bits.spaddr := Mux(read_issue_q.io.deq.bits.laddr.is_acc_addr,
      read_issue_q.io.deq.bits.laddr.full_acc_addr(), read_issue_q.io.deq.bits.laddr.full_sp_addr())
    reader.module.io.req.bits.len := read_issue_q.io.deq.bits.cols
    reader.module.io.req.bits.repeats := read_issue_q.io.deq.bits.repeats
    reader.module.io.req.bits.scale := read_issue_q.io.deq.bits.scale
    reader.module.io.req.bits.is_acc := read_issue_q.io.deq.bits.laddr.is_acc_addr
    reader.module.io.req.bits.accumulate := read_issue_q.io.deq.bits.laddr.accumulate
    reader.module.io.req.bits.has_acc_bitwidth := read_issue_q.io.deq.bits.has_acc_bitwidth
    reader.module.io.req.bits.block_stride := read_issue_q.io.deq.bits.block_stride
    reader.module.io.req.bits.status := read_issue_q.io.deq.bits.status
    reader.module.io.req.bits.cmd_id := read_issue_q.io.deq.bits.cmd_id

    val (mvin_scale_in, mvin_scale_out) = VectorScalarMultiplier(
      config.mvin_scale_args,
      config.inputType, config.meshColumns * config.tileColumns, chiselTypeOf(reader.module.io.resp.bits),
      is_acc = false
    )
    val (mvin_scale_acc_in, mvin_scale_acc_out) = if (mvin_scale_shared) (mvin_scale_in, mvin_scale_out) else (
      VectorScalarMultiplier(
        config.mvin_scale_acc_args,
        config.accType, config.meshColumns * config.tileColumns, chiselTypeOf(reader.module.io.resp.bits),
        is_acc = true
      )
    )

    mvin_scale_in.valid := reader.module.io.resp.valid && (mvin_scale_shared.B || !reader.module.io.resp.bits.is_acc ||
      (reader.module.io.resp.bits.is_acc && !reader.module.io.resp.bits.has_acc_bitwidth))

    mvin_scale_in.bits.in := reader.module.io.resp.bits.data.asTypeOf(chiselTypeOf(mvin_scale_in.bits.in))
    mvin_scale_in.bits.scale := reader.module.io.resp.bits.scale.asTypeOf(mvin_scale_t)
    mvin_scale_in.bits.repeats := reader.module.io.resp.bits.repeats
    mvin_scale_in.bits.last := reader.module.io.resp.bits.last
    mvin_scale_in.bits.tag := reader.module.io.resp.bits

    mvin_scale_out.ready := false.B

    if (!mvin_scale_shared) {
      mvin_scale_acc_in.valid := reader.module.io.resp.valid &&
        (reader.module.io.resp.bits.is_acc && reader.module.io.resp.bits.has_acc_bitwidth)
      mvin_scale_acc_in.bits.in := reader.module.io.resp.bits.data.asTypeOf(chiselTypeOf(mvin_scale_acc_in.bits.in))
      mvin_scale_acc_in.bits.scale := reader.module.io.resp.bits.scale.asTypeOf(mvin_scale_acc_t)
      mvin_scale_acc_in.bits.repeats := reader.module.io.resp.bits.repeats
      mvin_scale_acc_in.bits.last := reader.module.io.resp.bits.last
      mvin_scale_acc_in.bits.tag := reader.module.io.resp.bits

      mvin_scale_acc_out.ready := false.B
    }

    reader.module.io.resp.ready := Mux(reader.module.io.resp.bits.is_acc && reader.module.io.resp.bits.has_acc_bitwidth,
      mvin_scale_acc_in.ready, mvin_scale_in.ready)

    val mvin_scale_finished = mvin_scale_out.fire() && mvin_scale_out.bits.last
    val mvin_scale_acc_finished = mvin_scale_acc_out.fire() && mvin_scale_acc_out.bits.last
    val zero_writer_finished = zero_writer.io.resp.fire() && zero_writer.io.resp.bits.last

    val zero_writer_bytes_read = Mux(zero_writer.io.resp.bits.laddr.is_acc_addr,
      zero_writer.io.resp.bits.tag.cols * (accType.getWidth / 8).U,
      zero_writer.io.resp.bits.tag.cols * (inputType.getWidth / 8).U)

    // For DMA read responses, mvin_scale gets first priority, then mvin_scale_acc, and then zero_writer
    io.dma.read.resp.valid := mvin_scale_finished || mvin_scale_acc_finished || zero_writer_finished

    io.dma.read.resp.bits.cmd_id := MuxCase(zero_writer.io.resp.bits.tag.cmd_id, Seq(
      mvin_scale_finished -> mvin_scale_out.bits.tag.cmd_id,
      mvin_scale_acc_finished -> mvin_scale_acc_out.bits.tag.cmd_id))

    io.dma.read.resp.bits.bytesRead := MuxCase(zero_writer_bytes_read, Seq(
      mvin_scale_finished -> mvin_scale_out.bits.tag.bytes_read,
      mvin_scale_acc_finished -> mvin_scale_acc_out.bits.tag.bytes_read))

    io.tlb(0) <> writer.module.io.tlb
    io.tlb(1) <> reader.module.io.tlb

    writer.module.io.flush := io.flush
    reader.module.io.flush := io.flush

    io.busy := writer.module.io.busy || reader.module.io.busy || write_issue_q.io.deq.valid || write_scale_q.io.deq.valid || write_dispatch_q.valid

    {
      val banks = Seq.fill(sp_banks) { Module(new ScratchpadBank(sp_bank_entries, spad_w, aligned_to, config.sp_singleported)) }
      val bank_ios = VecInit(banks.map(_.io))

      // Reading from the SRAM banks
      bank_ios.zipWithIndex.foreach { case (bio, i) =>
        val ex_read_req = io.srams.read(i).req
        val exread = ex_read_req.valid

        // TODO we tie the write dispatch queue's, and write issue queue's, ready and valid signals together here
        val dmawrite = write_dispatch_q.valid && write_scale_q.io.enq.ready &&
          !write_dispatch_q.bits.laddr.is_garbage() &&
          !(bio.write.en && config.sp_singleported.B) &&
          !write_dispatch_q.bits.laddr.is_acc_addr && write_dispatch_q.bits.laddr.sp_bank() === i.U

        bio.read.req.valid := exread || dmawrite
        ex_read_req.ready := bio.read.req.ready

        // The ExecuteController gets priority when reading from SRAMs
        when (exread) {
          bio.read.req.bits.addr := ex_read_req.bits.addr
          bio.read.req.bits.fromDMA := false.B

          // soc class scratchpad state print 
          printf(SynthesizePrintf("EX,%d,%d\n", i.U(log2Ceil(sp_banks).W), ex_read_req.bits.addr ))

        }.elsewhen (dmawrite) {
          bio.read.req.bits.addr := write_dispatch_q.bits.laddr.sp_row()
          bio.read.req.bits.fromDMA := true.B

          when (bio.read.req.fire()) {
            write_dispatch_q.ready := true.B
            write_scale_q.io.enq.valid := true.B

            io.dma.write.resp.valid := true.B
          }
        }.otherwise {
          bio.read.req.bits := DontCare
        }

        val dma_read_resp = Wire(Decoupled(new ScratchpadReadResp(spad_w)))
        dma_read_resp.valid := bio.read.resp.valid && bio.read.resp.bits.fromDMA
        dma_read_resp.bits := bio.read.resp.bits
        val ex_read_resp = Wire(Decoupled(new ScratchpadReadResp(spad_w)))
        ex_read_resp.valid := bio.read.resp.valid && !bio.read.resp.bits.fromDMA
        ex_read_resp.bits := bio.read.resp.bits

        val dma_read_pipe = Pipeline(dma_read_resp, mem_pipeline)
        val ex_read_pipe = Pipeline(ex_read_resp, mem_pipeline)


        bio.read.resp.ready := Mux(bio.read.resp.bits.fromDMA, dma_read_resp.ready, ex_read_resp.ready)

        dma_read_pipe.ready := writer.module.io.req.ready &&
          !write_issue_q.io.deq.bits.laddr.is_acc_addr && write_issue_q.io.deq.bits.laddr.sp_bank() === i.U && // I believe we don't need to check that write_issue_q is valid here, because if the SRAM's resp is valid, then that means that the write_issue_q's deq should also be valid
          !write_issue_q.io.deq.bits.laddr.is_garbage()
        when (dma_read_pipe.fire()) {
          writeData.valid := true.B
          writeData.bits := dma_read_pipe.bits.data
        }

        io.srams.read(i).resp <> ex_read_pipe
      }

      // Writing to the SRAM banks
      bank_ios.zipWithIndex.foreach { case (bio, i) =>
        val exwrite = io.srams.write(i).en

        val laddr = mvin_scale_out.bits.tag.addr.asTypeOf(local_addr_t) + mvin_scale_out.bits.row

        val dmaread = mvin_scale_out.valid && !mvin_scale_out.bits.tag.is_acc &&
          laddr.sp_bank() === i.U

        // We need to make sure that we don't try to return a dma read resp from both zero_writer and either mvin_scale
        // or mvin_acc_scale at the same time. The scalers always get priority in those cases
        val zerowrite = zero_writer.io.resp.valid && !zero_writer.io.resp.bits.laddr.is_acc_addr &&
          zero_writer.io.resp.bits.laddr.sp_bank() === i.U &&
          !((mvin_scale_out.valid && mvin_scale_out.bits.last) || (mvin_scale_acc_out.valid && mvin_scale_acc_out.bits.last))

        bio.write.en := exwrite || dmaread || zerowrite


        when (exwrite) {
          bio.write.addr := io.srams.write(i).addr
          bio.write.data := io.srams.write(i).data
          bio.write.mask := io.srams.write(i).mask
        }.elsewhen (dmaread) {
          bio.write.addr := laddr.sp_row()
          bio.write.data := mvin_scale_out.bits.out.asUInt()
          bio.write.mask := mvin_scale_out.bits.tag.mask take ((spad_w / (aligned_to * 8)) max 1)

          mvin_scale_out.ready := true.B // TODO we combinationally couple valid and ready signals

          // soc class scratchpad state print 
          printf(SynthesizePrintf("LD,%d,%d\n", laddr.sp_bank(), laddr.sp_row() ))
        }.elsewhen (zerowrite) {
          bio.write.addr := zero_writer.io.resp.bits.laddr.sp_row()
          bio.write.data := 0.U
          bio.write.mask := {
            val n = inputType.getWidth / 8
            val mask = zero_writer.io.resp.bits.mask
            val expanded = VecInit(mask.flatMap(e => Seq.fill(n)(e)))
            expanded
          }

          zero_writer.io.resp.ready := true.B // TODO we combinationally couple valid and ready signals
        }.otherwise {
          bio.write.addr := DontCare
          bio.write.data := DontCare
          bio.write.mask := DontCare
        }
      }
    }

    val acc_row_t = Vec(meshColumns, Vec(tileColumns, accType))
    val spad_row_t = Vec(meshColumns, Vec(tileColumns, inputType))

    val acc_scale_unit = Module(new AccumulatorScale(
      acc_row_t,
      spad_row_t,
      acc_scale_args.multiplicand_t,
      log2Up(accType.getWidth),
      acc_read_small_width,
      acc_read_full_width,
      acc_scale_args
    ))

    acc_scale_unit.io.in.valid := false.B
    acc_scale_unit.io.in.bits  := DontCare
    val dma_resp_ready = (
      writer.module.io.req.ready &&
      write_issue_q.io.deq.bits.laddr.is_acc_addr &&
      !write_issue_q.io.deq.bits.laddr.is_garbage()
    )
    acc_scale_unit.io.out.ready := false.B
    when (acc_scale_unit.io.out.bits.fromDMA && dma_resp_ready) {
      acc_scale_unit.io.out.ready := true.B
      writeData.valid := acc_scale_unit.io.out.valid
      writeData.bits  := acc_scale_unit.io.out.bits.data.asUInt
      fullAccWriteData := acc_scale_unit.io.out.bits.full_data.asUInt
    }
    for (i <- 0 until acc_banks) {
      io.acc.read_resp(i).valid := false.B
      io.acc.read_resp(i).bits  := acc_scale_unit.io.out.bits
      when (!acc_scale_unit.io.out.bits.fromDMA && acc_scale_unit.io.out.bits.acc_bank_id === i.U) {
        acc_scale_unit.io.out.ready := io.acc.read_resp(i).ready
        io.acc.read_resp(i).valid := acc_scale_unit.io.out.valid
      }
    }

    {

      val banks = Seq.fill(acc_banks) { Module(new AccumulatorMem(
        acc_bank_entries, acc_row_t, acc_scale_args,
        acc_singleported, num_acc_sub_banks
      )) }
      val bank_ios = VecInit(banks.map(_.io))

      // Getting the output of the bank that's about to be issued to the writer
      val bank_issued_io = bank_ios(write_issue_q.io.deq.bits.laddr.acc_bank())

      // Reading from the Accumulator banks
      bank_ios.zipWithIndex.foreach { case (bio, i) =>
        val ex_read_req = io.acc.read_req(i)
        val exread = ex_read_req.valid

        // TODO we tie the write dispatch queue's, and write issue queue's, ready and valid signals together here
        val dmawrite = write_dispatch_q.valid && write_scale_q.io.enq.ready &&
          !write_dispatch_q.bits.laddr.is_garbage() &&
          write_dispatch_q.bits.laddr.is_acc_addr && write_dispatch_q.bits.laddr.acc_bank() === i.U

        bio.read.req.valid := exread || dmawrite
        bio.read.req.bits.scale := ex_read_req.bits.scale
        bio.read.req.bits.relu6_shift := ex_read_req.bits.relu6_shift
        bio.read.req.bits.act := ex_read_req.bits.act
        ex_read_req.ready := bio.read.req.ready

        // The ExecuteController gets priority when reading from accumulator banks
        when (exread) {
          bio.read.req.bits.addr := ex_read_req.bits.addr
          bio.read.req.bits.full := false.B
          bio.read.req.bits.fromDMA := false.B
        }.elsewhen (dmawrite) {
          bio.read.req.bits.addr := write_dispatch_q.bits.laddr.acc_row()
          bio.read.req.bits.full := write_dispatch_q.bits.laddr.read_full_acc_row
          bio.read.req.bits.fromDMA := true.B

          when (bio.read.req.fire()) {
            write_dispatch_q.ready := true.B
            write_scale_q.io.enq.valid := true.B

            io.dma.write.resp.valid := true.B
          }
        }.otherwise {
          bio.read.req.bits := DontCare
        }
        bio.read.resp.ready          := false.B


        when (write_scale_q.io.deq.valid &&
              acc_scale_unit.io.in.ready &&
              bio.read.resp.valid &&
              write_issue_q.io.enq.ready &&
              write_scale_q.io.deq.bits.laddr.is_acc_addr &&
              !write_scale_q.io.deq.bits.laddr.is_garbage() &&
              write_scale_q.io.deq.bits.laddr.acc_bank() === i.U) {
          write_scale_q.io.deq.ready   := true.B
          acc_scale_unit.io.in.valid := true.B
          bio.read.resp.ready          := true.B
          write_issue_q.io.enq.valid   := true.B

          acc_scale_unit.io.in.bits  := bio.read.resp.bits
          acc_scale_unit.io.in.bits.acc_bank_id := i.U
        }

      }

      // Writing to the accumulator banks
      bank_ios.zipWithIndex.foreach { case (bio, i) =>
        // Order of precedence during writes is ExecuteController, and then mvin_scale, and then mvin_scale_acc, and
        // then zero_writer

        val exwrite = io.acc.write(i).valid
        io.acc.write(i).ready := true.B
        assert(!(exwrite && !bio.write.ready), "Execute controller write to AccumulatorMem was skipped")

        val from_mvin_scale = mvin_scale_out.valid && mvin_scale_out.bits.tag.is_acc
        val from_mvin_scale_acc = mvin_scale_acc_out.valid && mvin_scale_acc_out.bits.tag.is_acc

        val mvin_scale_laddr = mvin_scale_out.bits.tag.addr.asTypeOf(local_addr_t) + mvin_scale_out.bits.row
        val mvin_scale_acc_laddr = mvin_scale_acc_out.bits.tag.addr.asTypeOf(local_addr_t) + mvin_scale_acc_out.bits.row

        val dmaread_bank = Mux(from_mvin_scale, mvin_scale_laddr.acc_bank(),
          mvin_scale_acc_laddr.acc_bank())
        val dmaread_row = Mux(from_mvin_scale, mvin_scale_laddr.acc_row(), mvin_scale_acc_laddr.acc_row())

        // We need to make sure that we don't try to return a dma read resp from both mvin_scale and mvin_scale_acc
        // at the same time. mvin_scale always gets priority in this cases
        val spad_last = mvin_scale_out.valid && mvin_scale_out.bits.last && !mvin_scale_out.bits.tag.is_acc

        val dmaread = (from_mvin_scale || from_mvin_scale_acc) &&
          dmaread_bank === i.U /* &&
          (mvin_scale_same.B || from_mvin_scale || !spad_dmaread_last) */

        // We need to make sure that we don't try to return a dma read resp from both zero_writer and either mvin_scale
        // or mvin_acc_scale at the same time. The scalers always get priority in those cases
        val zerowrite = zero_writer.io.resp.valid && zero_writer.io.resp.bits.laddr.is_acc_addr &&
          zero_writer.io.resp.bits.laddr.acc_bank() === i.U &&
          !((mvin_scale_out.valid && mvin_scale_out.bits.last) || (mvin_scale_acc_out.valid && mvin_scale_acc_out.bits.last))
        val consecutive_write_block = RegInit(false.B)
        if (acc_singleported) {
          val consecutive_write_sub_bank = RegInit(0.U((1 max log2Ceil(num_acc_sub_banks)).W))
          when (bio.write.fire() && bio.write.bits.acc &&
            (bio.write.bits.addr(log2Ceil(num_acc_sub_banks)-1,0) === consecutive_write_sub_bank)) {
            consecutive_write_block := true.B
          } .elsewhen (bio.write.fire() && bio.write.bits.acc) {
            consecutive_write_block := false.B
            consecutive_write_sub_bank := bio.write.bits.addr(log2Ceil(num_acc_sub_banks)-1,0)
          } .otherwise {
            consecutive_write_block := false.B
          }
        }
        bio.write.valid := false.B

        bio.write.bits.acc := MuxCase(zero_writer.io.resp.bits.laddr.accumulate,
          Seq(exwrite -> io.acc.write(i).bits.acc,
            from_mvin_scale -> mvin_scale_out.bits.tag.accumulate,
            from_mvin_scale_acc -> mvin_scale_acc_out.bits.tag.accumulate))

        bio.write.bits.addr := MuxCase(zero_writer.io.resp.bits.laddr.acc_row(),
          Seq(exwrite -> io.acc.write(i).bits.addr,
            (from_mvin_scale || from_mvin_scale_acc) -> dmaread_row))

        when (exwrite) {
          bio.write.valid := true.B
          bio.write.bits.data := io.acc.write(i).bits.data
          bio.write.bits.mask := io.acc.write(i).bits.mask
        }.elsewhen (dmaread && !spad_last && !consecutive_write_block) {
          bio.write.valid := true.B
          bio.write.bits.data := Mux(from_mvin_scale,
            VecInit(mvin_scale_out.bits.out.map(e => e.withWidthOf(accType))).asTypeOf(acc_row_t),
            mvin_scale_acc_out.bits.out.asTypeOf(acc_row_t))
          bio.write.bits.mask :=
            Mux(from_mvin_scale,
              {
                val n = accType.getWidth / inputType.getWidth
                val mask = mvin_scale_out.bits.tag.mask take ((spad_w / (aligned_to * 8)) max 1)
                val expanded = VecInit(mask.flatMap(e => Seq.fill(n)(e)))
                expanded
              },
              mvin_scale_acc_out.bits.tag.mask)

          when(from_mvin_scale) {
            mvin_scale_out.ready := bio.write.ready
          }.otherwise {
            mvin_scale_acc_out.ready := bio.write.ready
          }
        }.elsewhen (zerowrite && !spad_last && !consecutive_write_block) {
          bio.write.valid := true.B
          bio.write.bits.data := 0.U.asTypeOf(acc_row_t)
          bio.write.bits.mask := {
            val n = accType.getWidth / 8
            val mask = zero_writer.io.resp.bits.mask
            val expanded = VecInit(mask.flatMap(e => Seq.fill(n)(e)))
            expanded
          }

          zero_writer.io.resp.ready := bio.write.ready
        }.otherwise {
          bio.write.bits.data := DontCare
          bio.write.bits.mask := DontCare
        }
      }
    }
  }
}
