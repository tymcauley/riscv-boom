//******************************************************************************
// Copyright (c) 2013 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Execution Units
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// The issue window schedules micro-ops onto a specific execution pipeline
// A given execution pipeline may contain multiple functional units; one or more
// read ports, and one or more writeports.

package boom.exu

import scala.collection.mutable.{ArrayBuffer}

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.rocket.{BP, SFenceReq, CSR}
import freechips.rocketchip.tile.{XLen, RoCCCoreIO}
import freechips.rocketchip.tile

import FUConstants._
import boom.common._
import boom.ifu.{GetPCFromFtqIO}
import boom.util._

/**
 * Response from Execution Unit. Bundles a MicroOp with data
 *
 * @param dataWidth width of the data coming from the execution unit
 */
class ExeUnitResp(val dataWidth: Int)(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val data = Bits(dataWidth.W)
  val predicated = Bool() // Was this predicated off?
}

class CSRResp(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val data = UInt(xLen.W)
  val addr = UInt(CSR.ADDRSZ.W)
}

/**
 * Floating Point flag response
 */
class FFlagsResp(implicit p: Parameters) extends BoomBundle
{
  val uop = new MicroOp()
  val flags = Bits(tile.FPConstants.FLAGS_SZ.W)
}


/**
 * Abstract Top level Execution Unit that wraps lower level functional units to make a
 * multi function execution unit.
 */
abstract class ExecutionUnit(
  val writesLlIrf      : Boolean       = false,
  val writesLlFrf      : Boolean       = false,
  val numBypassStages  : Int           = -1,
  val dataWidth        : Int           = -1,
  val alwaysBypassable : Boolean       = false,
  val hasAGen          : Boolean       = false,
  val hasDGen          : Boolean       = false,
  val hasCSR           : Boolean       = false,
  val hasJmp           : Boolean       = false,
  val hasAlu           : Boolean       = false,
  val hasFpu           : Boolean       = false,
  val hasMul           : Boolean       = false,
  val hasDiv           : Boolean       = false,
  val hasFdiv          : Boolean       = false,
  val hasIfpu          : Boolean       = false,
  val hasFpiu          : Boolean       = false,
  val hasRocc          : Boolean       = false
  )(implicit p: Parameters) extends BoomModule
{
  require (numBypassStages >= 0 && dataWidth >= 0)

  val io = IO(new Bundle {
    val fu_types = Output(Bits(FUC_SZ.W))

    val req      = Flipped(new DecoupledIO(new FuncUnitReq(dataWidth)))

    val resp     = Valid(new ExeUnitResp(xLen + 1))

    val ll_iresp = if (writesLlIrf) new DecoupledIO(new ExeUnitResp(dataWidth)) else null
    val ll_fresp = if (writesLlFrf) new DecoupledIO(new ExeUnitResp(dataWidth)) else null

    val fflags = if (hasFCSR) new Valid(new FFlagsResp) else null

    val sfence   = if (hasCSR) new Valid(new SFenceReq) else null
    val csr_resp = if (hasCSR) new Valid(new CSRResp) else null

    val bypass   = Output(Vec(numBypassStages, Valid(new ExeUnitResp(dataWidth))))
    val brupdate = Input(new BrUpdateInfo())


    // only used by the rocc unit
    val rocc = if (hasRocc) new RoCCShimCoreIO else null

    // only used by the branch unit
    val brinfo     = if (hasAlu) Output(Valid(new BrResolutionInfo)) else null
    val get_ftq_pc = if (hasJmp) Flipped(new GetPCFromFtqIO()) else null
    val status     = Input(new freechips.rocketchip.rocket.MStatus())

    // only used by the fpu unit
    val fcsr_rm = if (hasFCSR) Input(Bits(tile.FPConstants.RM_SZ.W)) else null

    // only used by the mem unit
    val agen = if (hasAGen) Output(Valid(new ExeUnitResp(xLen))) else null
    val dgen = if (hasDGen || hasFpiu) Output(Valid(new ExeUnitResp(xLen))) else null

    val kill = Input(Bool())
  })

  io.resp.bits.predicated := false.B

  if (writesLlIrf) {
    io.ll_iresp.bits.predicated := false.B
  }
  if (writesLlFrf) {
    io.ll_fresp.bits.predicated := false.B
  }

  require ((hasFpu || hasFdiv) ^ (hasAlu || hasMul || hasAGen || hasDGen || hasIfpu),
    "[execute] we no longer support mixing FP and Integer functional units in the same exe unit.")
  def hasFCSR = hasIfpu || hasFpu || hasFdiv


}

class MemExeUnit(
  hasAGen        : Boolean = false,
  hasDGen        : Boolean = false)
  (implicit p: Parameters)
    extends ExecutionUnit(
      dataWidth = p(tile.XLen),
      numBypassStages = 0,
      alwaysBypassable = false,
      hasAGen = hasAGen,
      hasDGen = hasDGen)
    with freechips.rocketchip.rocket.constants.MemoryOpConstants
{

  val out_str =
    BoomCoreStringPrefix("==MemExeUnit==") +
    (if (hasAGen)  BoomCoreStringPrefix(" - AGen") else "") +
    (if (hasDGen)  BoomCoreStringPrefix(" - DGen") else "")

  override def toString: String = out_str.toString


  // If we issue loads back-to-back endlessly (probably because we are executing some tight loop)
  // the store buffer will never drain, breaking the memory-model forward-progress guarantee
  // If we see a large number of loads saturate the LSU, pause for a cycle to let a store drain
  val loads_saturating = io.req.valid && io.req.bits.uop.uses_ldq
  val saturating_loads_counter = RegInit(0.U(5.W))
  when (loads_saturating) { saturating_loads_counter := saturating_loads_counter + 1.U }
  .otherwise { saturating_loads_counter := 0.U }
  val pause_mem = RegNext(loads_saturating) && saturating_loads_counter === ~(0.U(5.W))


  io.fu_types := Mux(hasAGen.B && !pause_mem, FU_AGEN, 0.U) | Mux(hasDGen.B, FU_DGEN, 0.U)

  assert (!(io.req.valid && io.req.bits.uop.fu_code_is(FU_STORE)))
  if (hasAGen) {
    val sum = (io.req.bits.rs1_data.asSInt + io.req.bits.imm_data.asSInt).asUInt
    val ea_sign = Mux(sum(vaddrBits-1), ~sum(63,vaddrBits) === 0.U,
                                         sum(63,vaddrBits) =/= 0.U)
    val effective_address = Cat(ea_sign, sum(vaddrBits-1,0)).asUInt

    io.agen.valid     := io.req.valid && io.req.bits.uop.fu_code_is(FU_AGEN)
    io.agen.bits.uop  := io.req.bits.uop
    io.agen.bits.data := Sext(effective_address, xLen)
  } else {
    assert(!(io.req.valid && io.req.bits.uop.fu_code_is(FU_AGEN)))
  }

  if (hasDGen) {
    io.dgen.valid     := io.req.valid && io.req.bits.uop.fu_code_is(FU_DGEN)
    io.dgen.bits.data := io.req.bits.rs1_data
    io.dgen.bits.uop  := io.req.bits.uop
  } else {
    assert(!(io.req.valid && io.req.bits.uop.fu_code_is(FU_DGEN)))
  }

}

class ALUExeUnit(
  hasJmp         : Boolean = false,
  hasCSR         : Boolean = false,
  hasMul         : Boolean = false,
  hasDiv         : Boolean = false,
  hasIfpu        : Boolean = false,
  hasRocc        : Boolean = false)
  (implicit p: Parameters)
  extends ExecutionUnit(
    writesLlIrf      = hasRocc,
    writesLlFrf      = hasIfpu && p(tile.TileKey).core.fpu != None,
    numBypassStages  = if (hasMul) (p(tile.TileKey).core match { case b: BoomCoreParams => b.imulLatency }) else 0,
    dataWidth        = p(tile.XLen) + 1,
    alwaysBypassable = !(hasJmp || hasMul || hasDiv || hasCSR || hasIfpu || hasRocc),
    hasCSR           = hasCSR,
    hasJmp           = hasJmp,
    hasAlu           = true,
    hasMul           = hasMul,
    hasDiv           = hasDiv,
    hasIfpu          = hasIfpu,
    hasRocc          = hasRocc)
  with freechips.rocketchip.rocket.constants.MemoryOpConstants
{
  val out_str =
    BoomCoreStringPrefix("==ExeUnit==") +
    (             BoomCoreStringPrefix(" - ALU")) +
    (if (hasMul)  BoomCoreStringPrefix(" - Mul") else "") +
    (if (hasDiv)  BoomCoreStringPrefix(" - Div") else "") +
    (if (hasIfpu) BoomCoreStringPrefix(" - IFPU") else "") +
    (if (hasRocc) BoomCoreStringPrefix(" - RoCC") else "")

  override def toString: String = out_str.toString

  val div_busy   = WireInit(false.B)
  val ifpu_ready = RegInit(false.B)

  // The Functional Units --------------------
  // Specifically the functional units with fast writeback to IRF
  val iresp_fu_units = ArrayBuffer[FunctionalUnit]()

  io.fu_types := FU_ALU |
                 Mux(hasMul.B, FU_MUL, 0.U) |
                 Mux(!div_busy && hasDiv.B, FU_DIV, 0.U) |
                 Mux(hasCSR.B, FU_CSR, 0.U) |
                 Mux(hasJmp.B, FU_JMP, 0.U) |
                 Mux(ifpu_ready && hasIfpu.B, FU_I2F, 0.U)


  // ALU Unit -------------------------------
  var alu = Module(new ALUUnit(isJmpUnit = hasJmp,
                               numStages = numBypassStages,
                               dataWidth = xLen))
  alu.io.req.valid := (
    io.req.valid &&
    (io.req.bits.uop.fu_code === FU_ALU ||
     io.req.bits.uop.fu_code === FU_JMP ||
    (io.req.bits.uop.fu_code === FU_CSR && !io.req.bits.uop.is_rocc)))
  //ROCC Rocc Commands are taken by the RoCC unit

  alu.io.req.bits   := io.req.bits
  alu.io.resp.ready := DontCare
  alu.io.brupdate   := io.brupdate
  alu.io.kill       := io.kill

  iresp_fu_units += alu

  // Bypassing only applies to ALU
  io.bypass := alu.io.bypass

  // branch unit is embedded inside the ALU
  io.brinfo := alu.io.brinfo
  if (hasJmp) {
    alu.io.get_ftq_pc <> io.get_ftq_pc
  }

  var rocc: RoCCShim = null
  if (hasRocc) {
    rocc = Module(new RoCCShim)
    rocc.io.req.valid         := io.req.valid && io.req.bits.uop.is_rocc
    rocc.io.req.bits          := io.req.bits
    rocc.io.brupdate          := io.brupdate // We should assert on this somewhere
    rocc.io.status            := io.status
    rocc.io.exception         := io.kill
    io.rocc                   <> rocc.io.core

    rocc.io.resp.ready        := io.ll_iresp.ready
    io.ll_iresp.valid         := rocc.io.resp.valid
    io.ll_iresp.bits.uop      := rocc.io.resp.bits.uop
    io.ll_iresp.bits.data     := rocc.io.resp.bits.data
  }


  // Pipelined, IMul Unit ------------------
  var imul: PipelinedMulUnit = null
  if (hasMul) {
    imul = Module(new PipelinedMulUnit(imulLatency, xLen))
    imul.io <> DontCare
    imul.io.req.valid         := io.req.valid && io.req.bits.uop.fu_code_is(FU_MUL)
    imul.io.req.bits          := io.req.bits
    imul.io.brupdate          := io.brupdate
    imul.io.kill              := io.kill
    iresp_fu_units += imul
  }

  var ifpu: IntToFPUnit = null
  if (hasIfpu) {
    ifpu = Module(new IntToFPUnit(latency=intToFpLatency))
    ifpu.io.req        <> io.req
    ifpu.io.req.valid  := io.req.valid && io.req.bits.uop.fu_code_is(FU_I2F)
    ifpu.io.fcsr_rm    := io.fcsr_rm
    ifpu.io.brupdate   <> io.brupdate
    ifpu.io.kill       := io.kill
    ifpu.io.resp.ready := DontCare

    io.fflags := ifpu.io.fflags

    // buffer up results since we share write-port on integer regfile.
    val queue = Module(new BranchKillableQueue(new ExeUnitResp(dataWidth),
      entries = intToFpLatency + 6)) // TODO being overly conservative
    queue.io.enq.valid       := ifpu.io.resp.valid
    queue.io.enq.bits.uop    := ifpu.io.resp.bits.uop
    queue.io.enq.bits.data   := ifpu.io.resp.bits.data
    queue.io.enq.bits.predicated := ifpu.io.resp.bits.predicated
    queue.io.brupdate := io.brupdate
    queue.io.flush    := io.kill

    io.ll_fresp <> queue.io.deq
    io.ll_fresp.valid := queue.io.deq.valid && !IsKilledByBranch(io.brupdate, queue.io.deq.bits)
    io.ll_fresp.bits.uop := UpdateBrMask(io.brupdate, queue.io.deq.bits.uop)
    ifpu_ready := RegNext(queue.io.count < 2.U)
    assert (!(queue.io.enq.valid && !queue.io.enq.ready))
  }

  // Div/Rem Unit -----------------------
  var div: DivUnit = null
  val div_resp_val = WireInit(false.B)
  if (hasDiv) {
    val divq = Module(new BranchKillableQueue(new FuncUnitReq(xLen), 3))
    divq.io.enq.valid := (io.req.valid && io.req.bits.uop.fu_code_is(FU_DIV))
    divq.io.enq.bits  := io.req.bits
    divq.io.brupdate  := io.brupdate
    divq.io.flush     := io.kill

    div = Module(new DivUnit(xLen))
    div.io <> DontCare
    div.io.req <> divq.io.deq
    div.io.req.valid := divq.io.deq.valid && !IsKilledByBranch(io.brupdate, divq.io.deq.bits)
    div.io.req.bits := UpdateBrMask(io.brupdate, divq.io.deq.bits)
    div.io.brupdate            := io.brupdate
    div.io.kill                := io.kill

    // share write port with the pipelined units
    div.io.resp.ready := !(iresp_fu_units.map(_.io.resp.valid).reduce(_|_))

    div_resp_val := div.io.resp.valid
    div_busy     := !divq.io.empty

    iresp_fu_units += div
  }

  if (hasCSR) {
    io.sfence.valid := io.req.valid && io.req.bits.uop.is_sfence
    io.sfence.bits.rs1 := io.req.bits.uop.mem_size(0)
    io.sfence.bits.rs2 := io.req.bits.uop.mem_size(1)
    io.sfence.bits.addr := io.req.bits.rs1_data
    io.sfence.bits.asid := io.req.bits.rs2_data
  }

  // Outputs (Write Port #0)  ---------------
  io.resp.valid     := iresp_fu_units.map(_.io.resp.valid).reduce(_|_)
  io.resp.bits.uop  := PriorityMux(iresp_fu_units.map(f =>
    (f.io.resp.valid, f.io.resp.bits.uop)))
  io.resp.bits.data := PriorityMux(iresp_fu_units.map(f =>
    (f.io.resp.valid, f.io.resp.bits.data)))
  io.resp.bits.predicated := PriorityMux(iresp_fu_units.map(f =>
    (f.io.resp.valid, f.io.resp.bits.predicated)))

  // pulled out for critical path reasons
  // TODO: Does this make sense as part of the iresp bundle?
  if (hasCSR) {
    io.csr_resp.valid     := alu.io.resp.valid
    io.csr_resp.bits.uop  := alu.io.resp.bits.uop
    io.csr_resp.bits.data := alu.io.resp.bits.data
    io.csr_resp.bits.addr := ShiftRegister(io.req.bits.imm_data, numBypassStages) // time this with the ALU response
  }


  assert ((PopCount(iresp_fu_units.map(_.io.resp.valid)) <= 1.U && !div_resp_val) ||
          (PopCount(iresp_fu_units.map(_.io.resp.valid)) <= 2.U && (div_resp_val)),
          "Multiple functional units are fighting over the write port.")
}

/**
 * FPU-only unit, with optional second write-port for ToInt micro-ops.
 *
 * @param hasFpu does the exe unit have a fpu
 * @param hasFdiv does the exe unit have a FP divider
 * @param hasFpiu does the exe unit have a FP to int unit
 */
class FPUExeUnit(
  hasFpu  : Boolean = true,
  hasFdiv : Boolean = false,
  hasFpiu : Boolean = false
  )
  (implicit p: Parameters)
  extends ExecutionUnit(
    writesLlIrf = hasFpiu,
    numBypassStages = 0,
    dataWidth = p(tile.TileKey).core.fpu.get.fLen + 1,
    hasFpu  = hasFpu,
    hasFdiv = hasFdiv,
    hasFpiu = hasFpiu
  ) with tile.HasFPUParameters
{
  val out_str =
    BoomCoreStringPrefix("==FpExeUnit==") +
    (if (hasFpu)  BoomCoreStringPrefix("- FPU (Latency: " + dfmaLatency + ")") else "") +
    (if (hasFdiv) BoomCoreStringPrefix("- FDiv/FSqrt") else "") +
    (if (hasFpiu) BoomCoreStringPrefix("- FPIU (writes to Integer RF)") else "")

  val fdiv_busy = WireInit(false.B)
  val fpiu_busy = WireInit(false.B)

  // The Functional Units --------------------
  val fu_units = ArrayBuffer[FunctionalUnit]()

  io.fu_types := Mux(hasFpu.B, FU_FPU, 0.U) |
                 Mux(!fdiv_busy && hasFdiv.B, FU_FDV, 0.U) |
                 Mux(!fpiu_busy && hasFpiu.B, FU_F2I, 0.U)

  io.fflags.valid := false.B
  io.fflags.bits := DontCare

  // FPU Unit -----------------------
  var fpu: FPUUnit = null
  if (hasFpu) {
    fpu = Module(new FPUUnit())
    fpu.io.req.valid         := io.req.valid &&
                                (io.req.bits.uop.fu_code_is(FU_FPU) ||
                                io.req.bits.uop.fu_code_is(FU_F2I)) // TODO move to using a separate unit
    fpu.io.req.bits          := io.req.bits
    fpu.io.fcsr_rm           := io.fcsr_rm
    fpu.io.brupdate          := io.brupdate
    fpu.io.kill              := io.kill
    fpu.io.resp.ready        := true.B

    when (fpu.io.resp.fire()) {
      io.fflags := fpu.io.fflags
    }


    fu_units += fpu
  }

  // FDiv/FSqrt Unit -----------------------
  var fdivsqrt: FDivSqrtUnit = null
  if (hasFdiv) {
    fdivsqrt = Module(new FDivSqrtUnit())
    fdivsqrt.io.req.valid         := io.req.valid && io.req.bits.uop.fu_code_is(FU_FDV)
    fdivsqrt.io.req.bits          := io.req.bits
    fdivsqrt.io.fcsr_rm           := io.fcsr_rm
    fdivsqrt.io.brupdate          := io.brupdate
    fdivsqrt.io.kill              := io.kill

    // share write port with the pipelined units
    fdivsqrt.io.resp.ready := !(fu_units.map(_.io.resp.valid).reduce(_|_)) // TODO PERF will get blocked by fpiu.

    fdiv_busy := !fdivsqrt.io.req.ready || (io.req.valid && io.req.bits.uop.fu_code_is(FU_FDV))

    when (fdivsqrt.io.resp.fire()) {
      io.fflags := fdivsqrt.io.fflags
    }

    fu_units += fdivsqrt
  }

  // Outputs (Write Port #0)  ---------------

  io.resp.valid       := fu_units.map(_.io.resp.valid).reduce(_|_) &&
                          !(fpu.io.resp.valid && fpu.io.resp.bits.uop.fu_code_is(FU_F2I))
  io.resp.bits.uop    := PriorityMux(fu_units.map(f => (f.io.resp.valid,
                                                         f.io.resp.bits.uop)))
  io.resp.bits.data:= PriorityMux(fu_units.map(f => (f.io.resp.valid, f.io.resp.bits.data)))

  // Outputs (Write Port #1) -- FpToInt Queuing Unit -----------------------

  if (hasFpiu) {
    // TODO instantiate our own fpiu; and remove it from fpu.scala.
    // buffer up results since we share write-port on integer regfile.
    val queue = Module(new BranchKillableQueue(new ExeUnitResp(dataWidth),
      entries = dfmaLatency + 3)) // TODO being overly conservative
    queue.io.enq.valid       := (fpu.io.resp.valid &&
                                 fpu.io.resp.bits.uop.fu_code_is(FU_F2I) &&
                                 !fpu.io.resp.bits.uop.uses_stq) // STA means store data gen for floating point
    queue.io.enq.bits.uop    := fpu.io.resp.bits.uop
    queue.io.enq.bits.data   := fpu.io.resp.bits.data
    queue.io.enq.bits.predicated := fpu.io.resp.bits.predicated
    queue.io.brupdate        := io.brupdate
    queue.io.flush           := io.kill

    assert (queue.io.enq.ready) // If this backs up, we've miscalculated the size of the queue.

    io.dgen.valid      := RegNext(io.req.valid && io.req.bits.uop.uses_stq && !IsKilledByBranch(io.brupdate, io.req.bits.uop))
    io.dgen.bits.uop   := RegNext(io.req.bits.uop)
    io.dgen.bits.data  := RegNext(ieee(io.req.bits.rs2_data))

    io.ll_iresp       <> queue.io.deq
    io.ll_iresp.valid := queue.io.deq.valid && !IsKilledByBranch(io.brupdate, queue.io.deq.bits)
    io.ll_iresp.bits  := UpdateBrMask(io.brupdate, queue.io.deq.bits)

    fpiu_busy := !(queue.io.empty)
  }

  override def toString: String = out_str.toString
}
