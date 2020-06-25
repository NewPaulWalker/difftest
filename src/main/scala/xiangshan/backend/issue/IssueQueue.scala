package xiangshan.backend.issue

import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.utils._

trait IQConst{
  val iqSize = 8
  val iqIdxWidth = log2Up(iqSize)
  val layer1Size = iqSize
  val layer2Size = iqSize/2
  val debug = false
}

sealed abstract class IQBundle extends XSBundle with IQConst
sealed abstract class IQModule extends XSModule with IQConst //with NeedImpl

sealed class CmpInputBundle extends IQBundle{
  val instRdy = Input(Bool())
  val roqIdx  = Input(UInt(RoqIdxWidth.W))
  val iqIdx   = Input(UInt(iqIdxWidth.W))
}


sealed class CompareCircuitUnit(layer: Int = 0, id: Int = 0) extends IQModule {
  val io = IO(new Bundle(){
    val in1 = new CmpInputBundle
    val in2 = new CmpInputBundle
    val out = Flipped(new CmpInputBundle)
  })

  val roqIdx1 = io.in1.roqIdx
  val roqIdx2 = io.in2.roqIdx
  val iqIdx1  = io.in1.iqIdx
  val iqIdx2  = io.in2.iqIdx

  val inst1Rdy = io.in1.instRdy
  val inst2Rdy = io.in2.instRdy

  io.out.instRdy := inst1Rdy | inst2Rdy
  io.out.roqIdx := roqIdx2
  io.out.iqIdx := iqIdx2

  when((inst1Rdy && !inst2Rdy) || (inst1Rdy && inst2Rdy && (roqIdx1 < roqIdx2))){
    io.out.roqIdx := roqIdx1
    io.out.iqIdx := iqIdx1
  }
  if(debug && (layer==3)) {
    printf("(%d)[CCU(L%did%d)] in1.ready:%d in1.index:%d || in1.ready:%d in1.index:%d || out.ready:%d out.index:%d\n",GTimer(),layer.asUInt,id.asUInt,inst1Rdy,iqIdx1,inst2Rdy,iqIdx2,io.out.instRdy,io.out.iqIdx)
  }


}

class IssueQueue(val fuTypeInt: BigInt, val wakeupCnt: Int, val bypassCnt: Int = 0, val fixedDelay: Int = 1) extends IQModule {

  val useBypass = bypassCnt > 0

  val io = IO(new Bundle() {
    // flush Issue Queue
    val redirect = Flipped(ValidIO(new Redirect))

    // enq Ctrl sigs at dispatch-2
    val enqCtrl = Flipped(DecoupledIO(new MicroOp))
    // enq Data at next cycle (regfile has 1 cycle latency)
    val enqData = Flipped(ValidIO(new ExuInput))

    //  broadcast selected uop to other issue queues which has bypasses
    val selectedUop = if(useBypass) ValidIO(new MicroOp) else null

    // send to exu
    val deq = DecoupledIO(new ExuInput)

    // listen to write back bus
    val wakeUpPorts = Vec(wakeupCnt, Flipped(ValidIO(new ExuOutput)))

    // use bypass uops to speculative wake-up
    val bypassUops = if(useBypass) Vec(bypassCnt, Flipped(ValidIO(new MicroOp))) else null
    val bypassData = if(useBypass) Vec(bypassCnt, Flipped(ValidIO(new ExuOutput))) else null
  })
  //---------------------------------------------------------
  // Issue Queue
  //---------------------------------------------------------

  //Tag Queue
  val ctrlFlow = Mem(iqSize,new CtrlFlow)
  val ctrlSig = Mem(iqSize,new CtrlSignals)
  val brMask  = RegInit(VecInit(Seq.fill(iqSize)(0.U(BrqSize.W))))
  val brTag  = RegInit(VecInit(Seq.fill(iqSize)(0.U(BrTagWidth.W))))
  val validReg = RegInit(VecInit(Seq.fill(iqSize)(false.B)))
  val validFire= WireInit(VecInit(Seq.fill(iqSize)(false.B)))
  val valid = validReg.asUInt & ~validFire.asUInt
  val src1Rdy = RegInit(VecInit(Seq.fill(iqSize)(false.B)))
  val src2Rdy = RegInit(VecInit(Seq.fill(iqSize)(false.B)))
  val src3Rdy = RegInit(VecInit(Seq.fill(iqSize)(false.B)))
  val prfSrc1 = Reg(Vec(iqSize, UInt(PhyRegIdxWidth.W)))
  val prfSrc2 = Reg(Vec(iqSize, UInt(PhyRegIdxWidth.W)))
  val prfSrc3 = Reg(Vec(iqSize, UInt(PhyRegIdxWidth.W)))
  val prfDest = Reg(Vec(iqSize, UInt(PhyRegIdxWidth.W)))
  val oldPDest = Reg(Vec(iqSize, UInt(PhyRegIdxWidth.W)))
  val freelistAllocPtr = Reg(Vec(iqSize, UInt(PhyRegIdxWidth.W)))
  val roqIdx  = Reg(Vec(iqSize, UInt(RoqIdxWidth.W)))

  val instRdy = WireInit(VecInit(List.tabulate(iqSize)(i => src1Rdy(i) && src2Rdy(i) && src3Rdy(i)&& valid(i))))

  
  //tag enqueue
  val iqEmty = !valid.asUInt.orR
  val iqFull =  valid.asUInt.andR
  val iqAllowIn = !iqFull 
  io.enqCtrl.ready := iqAllowIn

  //enqueue pointer
  val emptySlot = ~valid.asUInt
  val enqueueSelect = PriorityEncoder(emptySlot)
  assert(!(io.enqCtrl.valid && io.redirect.valid),"enqueue valid should be false when redirect valid")

  when(io.enqCtrl.fire()){
    ctrlFlow(enqueueSelect) := io.enqCtrl.bits.cf
    ctrlSig(enqueueSelect) := io.enqCtrl.bits.ctrl
    brMask(enqueueSelect) := io.enqCtrl.bits.brMask
    brTag(enqueueSelect) := io.enqCtrl.bits.brTag
    validReg(enqueueSelect) := true.B
    src1Rdy(enqueueSelect) := Mux(io.enqCtrl.bits.ctrl.src1Type =/= SrcType.reg , true.B ,io.enqCtrl.bits.src1State === SrcState.rdy)
    src2Rdy(enqueueSelect) := Mux(io.enqCtrl.bits.ctrl.src2Type =/= SrcType.reg , true.B ,io.enqCtrl.bits.src2State === SrcState.rdy)
    src3Rdy(enqueueSelect) := Mux(io.enqCtrl.bits.ctrl.src3Type =/= SrcType.reg , true.B ,io.enqCtrl.bits.src3State === SrcState.rdy)
    prfSrc1(enqueueSelect) := io.enqCtrl.bits.psrc1
    prfSrc2(enqueueSelect) := io.enqCtrl.bits.psrc2
    prfSrc3(enqueueSelect) := io.enqCtrl.bits.psrc3
    prfDest(enqueueSelect) := io.enqCtrl.bits.pdest
    oldPDest(enqueueSelect) := io.enqCtrl.bits.old_pdest
    freelistAllocPtr(enqueueSelect) := io.enqCtrl.bits.freelistAllocPtr
    roqIdx(enqueueSelect) := io.enqCtrl.bits.roqIdx
    if(debug) {printf("(%d)[IQ enq]: enqSelect:%d | s1Rd:%d s2Rd:%d s3Rd:%d\n",GTimer(),enqueueSelect.asUInt,
                                                                        (io.enqCtrl.bits.src1State === SrcState.rdy),
                                                                        (io.enqCtrl.bits.src2State === SrcState.rdy),
                                                                        (io.enqCtrl.bits.src3State === SrcState.rdy))}

  }

  //Data Queue
  val src1Data = Reg(Vec(iqSize, UInt(XLEN.W)))
  val src2Data = Reg(Vec(iqSize, UInt(XLEN.W)))
  val src3Data = Reg(Vec(iqSize, UInt(XLEN.W)))
  

  val enqSelNext = RegNext(enqueueSelect)
  val enqFireNext = RegNext(io.enqCtrl.fire())

  // Read RegFile
  //Ready data will written at next cycle
  when (enqFireNext) {
    when(src1Rdy(enqSelNext)){src1Data(enqSelNext) := io.enqData.bits.src1}
    when(src2Rdy(enqSelNext)){src2Data(enqSelNext) := io.enqData.bits.src2}
    when(src3Rdy(enqSelNext)){src3Data(enqSelNext) := io.enqData.bits.src3}
  }

  if(debug) {
    printf("(%d)[Reg info] enqSelNext:%d | enqFireNext:%d \n",GTimer(),enqSelNext,enqFireNext)
    printf("(%d)[IQ content] valid |   src1rdy  src1 |  src2Rdy  src2   pdest  \n",GTimer())
    for(i <- 0 to (iqSize -1)){
      printf("(%d)[IQ content][%d] %d%d%d | %x %x | %x %x | %d",GTimer(),i.asUInt, valid(i), validReg(i), validFire(i), src1Rdy(i), src1Data(i), src2Rdy(i), src2Data(i),prfDest(i))
      when(valid(i)){printf("  valid")}
      printf(" |\n")
    }
  }
  // From Common Data Bus(wakeUpPort)
  // chisel claims that firrtl will optimize Mux1H to and/or tree
  // TODO: ignore ALU'cdb srcRdy, for byPass has done it
  if(wakeupCnt > 0) {
    val cdbValid = List.tabulate(wakeupCnt)(i => io.wakeUpPorts(i).valid)
    val cdbData = List.tabulate(wakeupCnt)(i => io.wakeUpPorts(i).bits.data)
    val cdbPdest = List.tabulate(wakeupCnt)(i => io.wakeUpPorts(i).bits.uop.pdest)

    val srcNum = 3
    val prfSrc = List(prfSrc1, prfSrc2, prfSrc3)
    val srcRdy = List(src1Rdy, src2Rdy, src3Rdy)
    val srcData = List(src1Data, src2Data, src3Data)
    val srcHitVec = List.tabulate(srcNum)(k =>
                      List.tabulate(iqSize)(i =>
                        List.tabulate(wakeupCnt)(j =>
                          (prfSrc(k)(i) === cdbPdest(j)) && cdbValid(j))))
    val srcHit =  List.tabulate(srcNum)(k =>
                    List.tabulate(iqSize)(i =>
                      ParallelOR(srcHitVec(k)(i)).asBool()))
                      // VecInit(srcHitVec(k)(i)).asUInt.orR))
    for(k <- 0 until srcNum){
      for(i <- 0 until iqSize)( when (valid(i)) {
        when(!srcRdy(k)(i) && srcHit(k)(i)) {
          srcRdy(k)(i) := true.B
          // srcData(k)(i) := Mux1H(srcHitVec(k)(i), cdbData)
          srcData(k)(i) := ParallelMux(srcHitVec(k)(i) zip cdbData)
        }
      })
    }
    // From byPass [speculative] (just for ALU to listen to other ALU's res, include itself)
    // just need Tag(Ctrl). send out Tag when Tag is decided. other ALUIQ listen to them and decide Tag
    // byPassUops is one cycle before byPassDatas
    if (bypassCnt > 0) {
      val bypassPdest = List.tabulate(bypassCnt)(i => io.bypassUops(i).bits.pdest)
      val bypassValid = List.tabulate(bypassCnt)(i => io.bypassUops(i).valid) // may only need valid not fire()
      val bypassData = List.tabulate(bypassCnt)(i => io.bypassData(i).bits.data)
      val srcBpHitVec = List.tabulate(srcNum)(k =>
                          List.tabulate(iqSize)(i =>
                            List.tabulate(bypassCnt)(j =>
                              (prfSrc(k)(i) === bypassPdest(j)) && bypassValid(j))))
      val srcBpHit =  List.tabulate(srcNum)(k =>
                        List.tabulate(iqSize)(i =>
                          ParallelOR(srcBpHitVec(k)(i)).asBool()))
                          // VecInit(srcBpHitVec(k)(i)).asUInt.orR))
      val srcBpHitVecNext = List.tabulate(srcNum)(k =>
                              List.tabulate(iqSize)(i =>
                                List.tabulate(bypassCnt)(j => RegNext(srcBpHitVec(k)(i)(j)))))
      val srcBpHitNext = List.tabulate(srcNum)(k =>
                          List.tabulate(iqSize)(i =>
                            RegNext(srcBpHit(k)(i))))
      val srcBpData = List.tabulate(srcNum)(k =>
                        List.tabulate(iqSize)(i => 
                          ParallelMux(srcBpHitVecNext(k)(i) zip bypassData)))
                          // Mux1H(srcBpHitVecNext(k)(i), bypassData)))
      for(k <- 0 until srcNum){
        for(i <- 0 until iqSize){ when (valid(i)) {
          when(valid(i) && !srcRdy(k)(i) && srcBpHit(k)(i)) { srcRdy(k)(i) := true.B }
          when(srcBpHitNext(k)(i)) { srcData(k)(i) := srcBpData(k)(i)}
        }}
      }
    }
  }


  //---------------------------------------------------------
  // Select Circuit
  //---------------------------------------------------------
  //layer 1
  val layer1CCUs = (0 until layer1Size by 2) map { i =>
    val CCU_1 = Module(new CompareCircuitUnit(layer = 1, id = i/2))
    CCU_1.io.in1.instRdy := instRdy(i)
    CCU_1.io.in1.roqIdx  := roqIdx(i)
    CCU_1.io.in1.iqIdx   := i.U

    CCU_1.io.in2.instRdy := instRdy(i+1)
    CCU_1.io.in2.roqIdx  := roqIdx(i+1)
    CCU_1.io.in2.iqIdx   := (i+1).U
    
    CCU_1
  }

  //layer 2
  val layer2CCUs = (0 until layer2Size by 2) map { i =>
    val CCU_2 = Module(new CompareCircuitUnit(layer = 2, id = i/2))
    CCU_2.io.in1.instRdy := layer1CCUs(i).io.out.instRdy
    CCU_2.io.in1.roqIdx  := layer1CCUs(i).io.out.roqIdx
    CCU_2.io.in1.iqIdx   := layer1CCUs(i).io.out.iqIdx

    CCU_2.io.in2.instRdy := layer1CCUs(i+1).io.out.instRdy
    CCU_2.io.in2.roqIdx  := layer1CCUs(i+1).io.out.roqIdx
    CCU_2.io.in2.iqIdx   := layer1CCUs(i+1).io.out.iqIdx
    
    CCU_2
  }

  //layer 3
  val CCU_3 = Module(new CompareCircuitUnit(layer = 3, id = 0))
  CCU_3.io.in1.instRdy := layer2CCUs(0).io.out.instRdy
  CCU_3.io.in1.roqIdx  := layer2CCUs(0).io.out.roqIdx
  CCU_3.io.in1.iqIdx   := layer2CCUs(0).io.out.iqIdx

  CCU_3.io.in2.instRdy := layer2CCUs(1).io.out.instRdy
  CCU_3.io.in2.roqIdx  := layer2CCUs(1).io.out.roqIdx
  CCU_3.io.in2.iqIdx   := layer2CCUs(1).io.out.iqIdx


  //---------------------------------------------------------
  // Redirect Logic
  //---------------------------------------------------------
  val expRedirect = io.redirect.valid && io.redirect.bits.isException
  val brRedirect = io.redirect.valid && !io.redirect.bits.isException

  List.tabulate(iqSize)( i =>
    when(brRedirect && (UIntToOH(io.redirect.bits.brTag) & brMask(i)).orR && valid(i) ){
        validReg(i) := false.B
    } .elsewhen(expRedirect) {
        validReg(i) := false.B
    }
  )
  //---------------------------------------------------------
  // Dequeue Logic
  //---------------------------------------------------------
  //hold the sel-index to wait for data
  val selInstIdx = RegInit(0.U(iqIdxWidth.W))
  val selInstRdy = RegInit(false.B)

  //issue the select instruction
  val dequeueSelect = Wire(UInt(iqIdxWidth.W))
  dequeueSelect := selInstIdx

  val brRedirectMaskMatch = (UIntToOH(io.redirect.bits.brTag) & brMask(dequeueSelect)).orR
  val IQreadyGo = selInstRdy && !expRedirect && (!brRedirect || !brRedirectMaskMatch)

  io.deq.valid := IQreadyGo

  io.deq.bits.uop.cf := ctrlFlow(dequeueSelect)
  io.deq.bits.uop.ctrl := ctrlSig(dequeueSelect)
  io.deq.bits.uop.brMask := brMask(dequeueSelect)
  io.deq.bits.uop.brTag := brTag(dequeueSelect)

  io.deq.bits.uop.psrc1 := prfSrc1(dequeueSelect)
  io.deq.bits.uop.psrc2 := prfSrc2(dequeueSelect)
  io.deq.bits.uop.psrc3 := prfSrc3(dequeueSelect)
  io.deq.bits.uop.pdest := prfDest(dequeueSelect)
  io.deq.bits.uop.old_pdest := oldPDest(dequeueSelect)
  io.deq.bits.uop.src1State := SrcState.rdy
  io.deq.bits.uop.src2State := SrcState.rdy
  io.deq.bits.uop.src3State := SrcState.rdy
  io.deq.bits.uop.freelistAllocPtr := freelistAllocPtr(dequeueSelect)
  io.deq.bits.uop.roqIdx := roqIdx(dequeueSelect)

  io.deq.bits.src1 := src1Data(dequeueSelect)
  io.deq.bits.src2 := src2Data(dequeueSelect)
  io.deq.bits.src3 := src3Data(dequeueSelect)

  if(debug) {
    printf("(%d)[Sel Reg] selInstRdy:%d || selIdx:%d\n",GTimer(),selInstRdy,selInstIdx.asUInt)
    when(IQreadyGo){printf("(%d)[IQ dequeue] **fire:%d** roqIdx:%d dequeueSel:%d | src1Rd:%d src1:%d | src2Rd:%d src2:%d\n",GTimer(), io.deq.fire(), io.deq.bits.uop.roqIdx, dequeueSelect.asUInt,
                              (io.deq.bits.uop.src1State === SrcState.rdy), io.deq.bits.uop.psrc1,
                              (io.deq.bits.uop.src2State === SrcState.rdy), io.deq.bits.uop.psrc2
                              )}
  }

  //update the index register of instruction that can be issue, unless function unit not allow in
  //then the issue will be stopped to wait the function unit 
  //clear the validBit of dequeued instruction in issuequeue
  when(io.deq.fire()){
    validReg(dequeueSelect) := false.B
    validFire(dequeueSelect) := true.B
  }

  val selRegflush = expRedirect || (brRedirect && brRedirectMaskMatch)
  selInstRdy := Mux(selRegflush,false.B,CCU_3.io.out.instRdy)
  selInstIdx := Mux(selRegflush,0.U,CCU_3.io.out.iqIdx)
  // SelectedUop (bypass / speculative)
  if(useBypass) {
    def DelayPipe[T <: Data](a: T, delay: Int = 0) = {
      // println(delay)
      if(delay == 0) a
      else {
        val storage = Wire(VecInit(Seq.fill(delay+1)(a)))
        // storage(0) := a
        for(i <- 1 until delay) {
          storage(i) := RegNext(storage(i-1))
        }
        storage(delay)
      }
    }
    val sel = io.selectedUop
    val selIQIdx = CCU_3.io.out.iqIdx
    val delayPipe = DelayPipe(VecInit(CCU_3.io.out.instRdy, prfDest(selIQIdx)), fixedDelay-1)
    sel.valid := delayPipe(fixedDelay-1)(0)
    sel.bits := DontCare
    sel.bits.pdest := delayPipe(fixedDelay-1)(1)
  }
}