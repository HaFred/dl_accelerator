package dla.tests

import dla.cluster.{ClusterSRAMConfig, GNMFCS1Config, GNMFCS2Config}
import dla.pe.{MCRENFConfig, PESizeConfig, SPadSizeConfig}
import org.scalatest._
import scala.math.max

class ScalaModel extends FlatSpec with PESizeConfig with SPadSizeConfig
  with MCRENFConfig with GNMFCS1Config with GNMFCS2Config with ClusterSRAMConfig {
  private val sequencer = new GenOneStreamData
  private val scoreBoard = new ScoreBoard
  private val peNum = 12
  private val genFun = new GenFunc
  behavior of "compare the efficiency of Eyeriss"
  it should "get the info of Eyeriss dla" in {
    /** model the behavior of Eyeriss cluster group */
    val pSumResult: Array[Array[Array[Array[Int]]]] =
      Array.fill(G2 * G1 * N2 * N1 * N0) {
        Array.fill(M2 * M1 * M0) { // each number
          Array.fill(E) {
            Array.fill(F2 * F1 * F0) {
              0
            }
          }
        }
      }
    val monitor = new CompareMonitor
    /** TODO: add inActMem access */
    val inActMemNum = sequencer.inActStreamTmp.flatten.flatten.length
    monitor.inActAccess.mem += inActMemNum
    monitor.cycle += inActMemNum*scoreBoard.accessCost.mem
    var parallelCycle = 0
    for (g2 <- 0 until G2) {
      for (n2 <- 0 until N2) {
        for (m2 <- 0 until M2) {
          for (f2 <- 0 until F2) {
            for (c2 <- 0 until C2) {
              for (s2 <- 0 until S2) {
                val weightReadAdr = g2*M2*C2*S2 + m2*C2*S2 + c2*S2 + s2
                val inActReadAdr = g2*N2*C2*(F2 + S2) + n2*C2*(F2+S2) + c2*(F2+S2) + f2+s2
                /** NoC level */
                for (g1 <- 0 until G1) {
                  for (n1 <- 0 until N1) {
                    for (m1 <- 0 until M1) {
                      for (f1 <- 0 until F1) {
                        for (c1 <- 0 until C1) {
                          for (s1 <- 0 until S1) {
                            /** SPad level */
                            val weightGLBIdx = weightReadAdr*weightParNum + g1*M1*C1*S1 + m1*C1*S1 + c1*S1 + s1
                            val inActGLBIdx = inActReadAdr*inActParNum + g1*N1*C1*(F1+S1) + n1*C1*(F1+S1) + c1*(F1+S1) + (f1+s1)
                            require(inActGLBIdx < sequencer.inActAdrStreamTmp.length,
                              s"$inActGLBIdx, ${sequencer.inActAdrStreamTmp.length}")
                            val inActAdrSPad = sequencer.inActAdrStreamTmp(inActGLBIdx)
                            val inActDataSPad = genFun.combineDataAndCount(sequencer.inActDataStreamTmp(inActGLBIdx),
                              sequencer.inActCountStreamTmp(inActGLBIdx))
                            val weightAdrSPad = sequencer.weightAdrStreamTmp(weightGLBIdx)
                            val weightDataSPad = genFun.combineDataAndCount(sequencer.weightDataStreamTmp(weightGLBIdx),
                              sequencer.weightCountStreamTmp(weightGLBIdx))
                            val pSumSPad: Array[Array[Int]] = Array.fill(F0*N0*E, M0) {0}
                            val inActGLBAccessNumMax = max(inActAdrSPad.length, inActDataSPad.length)
                            val weightMemAccessNum = sequencer.weightStream(weightGLBIdx).flatten.length
                            monitor.inActAccess.glb += inActAdrSPad.length + inActDataSPad.length
                            monitor.weightAccess.mem += weightMemAccessNum
                            parallelCycle += max(inActGLBAccessNumMax*scoreBoard.accessCost.glb,
                              weightMemAccessNum*scoreBoard.accessCost.mem)
                            var inActDataSPadIdx = 0
                            var inActFirstRead = true
                            for (inActAdrSPadIdx <- inActAdrSPad.indices) {
                              /** padInActAdr */
                              val inActAdr = inActAdrSPad(inActAdrSPadIdx)
                              monitor.inActAccess.sPad += 1
                              parallelCycle += 1
                              if (inActAdr != inActZeroColumnCode || inActAdr == 0) {
                                if (!inActFirstRead) {
                                  inActDataSPadIdx += 1
                                }
                                /** padInActData */
                                while (inActDataSPadIdx < inActAdr) {
                                  val inActDataRead = inActDataSPad(inActDataSPadIdx)
                                  val inActData = BigInt(inActDataRead.toBinaryString.take(cscDataWidth), 2).toInt
                                  val inActRow = BigInt(inActDataRead.toBinaryString.takeRight(cscCountWidth), 2).toInt
                                  monitor.inActAccess.sPad += 1
                                  parallelCycle += 1
                                  if (inActDataRead != 0) {
                                    /** padWeightAdr */
                                    inActFirstRead = false
                                    val weightAdr = weightAdrSPad(inActRow)
                                    val weightDataSPadStartIdx = if (inActRow == 0) 0 else weightAdrSPad(inActRow - 1)
                                    monitor.weightAccess.sPad += 1
                                    parallelCycle += 1
                                    if (weightAdr != weightZeroColumnCode || weightAdr == 0) {
                                      /** padWeightData */
                                      for (weightDataSPadIdx <- weightDataSPadStartIdx until weightAdr) {
                                        val weightDataRead = weightDataSPad(weightDataSPadIdx)
                                        val weightData = BigInt(weightDataRead.toBinaryString.take(cscDataWidth), 2).toInt
                                        val weightRow = BigInt(weightDataRead.toBinaryString.takeRight(cscCountWidth), 2).toInt
                                        monitor.weightAccess.sPad += 1
                                        parallelCycle += 2 // need 2 cycles to read from SRAM
                                        pSumSPad(inActAdrSPadIdx)(weightRow) += weightData * inActData
                                        monitor.macNum += 1
                                        parallelCycle += 2 // one for mpy, one for write back
                                      }
                                    } else {
                                      inActDataSPadIdx += 1
                                    }
                                  }
                                  inActDataSPadIdx += 1
                                }
                              }
                            }
                            print(".") // finish SPad Level
                          }
                        }
                      }
                    }
                  }
                }
                print("*") // finish GLB Level
              }
            }
          }
        }
      }
    }
    monitor.cycle += parallelCycle/peNum
    monitor.printMonitorInfo()
  }

  it should "get the info of common data" in {
    /** read from main memory, can do 4*3 mac parallel */
    val pSumResult: Array[Array[Array[Array[Int]]]] =
      Array.fill(G2 * G1 * N2 * N1 * N0) {
        Array.fill(M2 * M1 * M0) { // each number
          Array.fill(E) {
            Array.fill(F2 * F1 * F0) {
              0
            }
          }
        }
      }
    val monitor = new CompareMonitor
    /** for every pSum */
    for (g <- 0 until G2*G1) {
      /** each PSum number */
      for (n <- 0 until N2*N1) {
        for (n0 <- 0 until N0) {
          /** each PSum channel*/
          for (m <- 0 until M2*M1) {
            for (m0 <- 0 until M0) {
              /** PSum height */
              for (e <- 0 until E) {
                /** PSum width*/
                for (f2 <- 0 until F2) {
                  for (f1 <- 0 until F1) {
                    for (f0 <- 0 until F0) {
                      /** inside this for loop, do mac, for the size of weight matrix */
                      /** weight channel */
                      for (c <- 0 until C2*C1) {
                        for (c0 <- 0 until C0) {
                          /** weight height */
                          for (r <- 0 until R) {
                            /** weight width */
                            for (s2 <- 0 until S2) {
                              for (s1 <- 0 until S1) {
                                val inActWidthIdx = f0*n0*e
                                val inActHeightIdx = r*c0
                                val inActSeqIdx = g*n*c*(f2 + s2)*(f1 + s1)
                                val weightWidthIdx = r*c0
                                val weightHeightIdx = m0
                                val weightSeqIdx = g*m*c*s2*s1
                                val macResult = sequencer.weightStreamTmp(weightSeqIdx)(weightWidthIdx)(weightHeightIdx) *
                                  sequencer.inActStreamTmp(inActSeqIdx)(inActWidthIdx)(inActHeightIdx)
                                pSumResult(g*n*n0)(m)(e)(f2*f1*f0) += macResult
                                monitor.inActAccess.mem += 1
                                monitor.weightAccess.mem += 1
                                monitor.macNum += 1
                              }
                            }
                          }
                        }
                      }
                    }
                    print(".") // finish one PSum
                  }
                }
              }
              print("*") // finish one PSum matrix
            }
          }
          println("\n[INFO] finish one batch of PSum " +
            f"${((g*N2*N1*N0 + n*N0 + n0 + 1).toFloat/(G2*G1*N2*N1*N0).toFloat)*100}%.2f%%")
        }
      }
    }
    monitor.cycle = scoreBoard.totalCycles(monitor.macNum, peNum, monitor.inActAccess.mem, 0, 0)
    monitor.printMonitorInfo()
  }
}

class CompareMonitor {
  var cycle: BigInt = 0 // the number of clock cycles
  var macNum: BigInt = 0 // the number of mac
  object inActAccess {
    var mem: BigInt = 0 // the times to access memory
    var glb: BigInt = 0 // the times to access glb sram
    var sPad: BigInt = 0 // the times to access sPad register
  }
  object weightAccess {
    var mem: BigInt = 0 // the times to access memory
    var sPad: BigInt = 0 // the times to access sPad register
  }
  def printMonitorInfo(): Unit = {
    println("[INFO] computation finishes")
    println(s"------ time = $cycle")
    println(s"------ mac = $macNum")
    println(s"------ inActAccess ")
    println(s"                   | mem: ${inActAccess.mem}")
    println(s"                   | glb: ${inActAccess.glb}")
    println(s"                   | sPad: ${inActAccess.sPad}")
    println(s"------ weightAccess")
    println(s"                   | mem: ${weightAccess.mem}")
    println(s"                   | sPad: ${weightAccess.sPad}")
  }
}

class ScoreBoard {
  /** [[macCost]]: every mac will cost 3 clock cycle
    * 0: multiply
    * 1: accumulate
    * 2: write back*/
  val macCost: Int = 3
  object accessCost { // every access will cost ? clock cycles
    val mem: Int = 60
    val glb: Int = 2
    val sPad: Int = 1
  }
  def totalCycles(macNum: BigInt, peNum: Int, memNum: BigInt, glbNum: BigInt, sPadNum: BigInt ): BigInt = {
    val cycles: BigInt = (macNum/peNum) * macCost + memNum * accessCost.mem +
      glbNum * accessCost.glb + sPadNum * accessCost.sPad
    cycles
  }
}