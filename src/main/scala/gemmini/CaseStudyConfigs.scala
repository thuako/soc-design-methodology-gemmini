package gemmini

import chipsalliance.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.SystemBusKey
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}

// Virtual memory case study
class VirtualGemminiConfigNoRegisterPrivateTLB4 extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      val gemmini = LazyModule(new Gemmini(OpcodeSet.custom3,
        GemminiConfigs.defaultConfig.copy(tlb_size = 4, max_in_flight_reqs = 256, use_tlb_register_filter = false)))
      gemmini
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 16)
})

class VirtualGemminiConfigNoRegisterPrivateTLB8 extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      val gemmini = LazyModule(new Gemmini(OpcodeSet.custom3,
        GemminiConfigs.defaultConfig.copy(tlb_size = 8, max_in_flight_reqs = 256, use_tlb_register_filter = false)))
      gemmini
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 16)
})

class VirtualGemminiConfigNoRegisterPrivateTLB16 extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      val gemmini = LazyModule(new Gemmini(OpcodeSet.custom3,
        GemminiConfigs.defaultConfig.copy(tlb_size = 16, max_in_flight_reqs = 256, use_tlb_register_filter = false)))
      gemmini
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 16)
})

// Memory contention
class MemoryGemminiConfigBaseSP extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      val gemmini = LazyModule(new Gemmini(OpcodeSet.custom3,
        GemminiConfigs.defaultConfig.copy(max_in_flight_reqs = 256, sp_capacity = CapacityInKilobytes(256),
          acc_capacity = CapacityInKilobytes(256))))
      gemmini
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 16)
})

class MemoryGemminiConfigBigSp extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      val gemmini = LazyModule(new Gemmini(OpcodeSet.custom3,
        GemminiConfigs.defaultConfig.copy(max_in_flight_reqs = 256, sp_capacity = CapacityInKilobytes(512),
          acc_capacity = CapacityInKilobytes(512))))
      gemmini
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 16)
})
