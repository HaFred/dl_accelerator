package dla.cluster

import chisel3._
import chisel3.experimental.{DataMirror, Direction}
import chisel3.util._

class ClusterGroup(debug: Boolean) extends Module with ClusterConfig {
  val io: ClusterGroupIO = IO(new ClusterGroupIO)
  private val peCluster = Module(new PECluster(debug)).io
  private val glbCluster = Module(new GLBCluster(debug)).io
  private val routerCluster = Module(new RouterCluster(debug)).io
  // doCalReg: true, then they are doing calculation; false, then read in data or read out data
  private val doCalReg: Bool = RegInit(false.B) // TODO: add some logic to change it
  // suggest name

  // connections of control path
  require(DataMirror.directionOf(io.ctrlPath.routerClusterCtrl.inActCtrlSel.inDataSel) == Direction.Input, "it should be input")
  routerCluster.ctrlPath.iRIO.foreach(_ <> io.ctrlPath.routerClusterCtrl.inActCtrlSel)
  routerCluster.ctrlPath.wRIO.foreach(_ <> io.ctrlPath.routerClusterCtrl.weightCtrlSel)
  routerCluster.ctrlPath.pSumRIO.foreach(_ <> io.ctrlPath.routerClusterCtrl.pSumCtrlSel)
  // true for broad-cast
  peCluster.ctrlPath.inActCtrlSel <> io.ctrlPath.peClusterCtrl
  peCluster.ctrlPath.pSumCtrlSel.inDataSel := DontCare // FIXME: select from outside or router
  // we can disable the outDataSel, as output of pSum is connected directly to router and outside
  peCluster.ctrlPath.pSumCtrlSel.outDataSel := DontCare
  // connections of data path
  // input activations
  for (i <- 0 until inActRouterNum) {
    // port 0  for GLB Cluster, 1 for north, 2 for south, 3 for horizontal
    // top connections
    glbCluster.dataPath.inActIO(i).inIOs <> io.dataPath.glbDataPath.inActIO(i).inIOs
    io.dataPath.glbDataPath.inActIO(i).outIOs <> DontCare // disable this IO
    for (j <- 1 until inActPortNum) {
      routerCluster.dataPath.routerData.iRIO(i).inIOs(j) <> io.dataPath.cgDataPath.iRIO(i).inIOs(j)
      io.dataPath.cgDataPath.iRIO(i).outIOs(j) <> routerCluster.dataPath.routerData.iRIO(i).outIOs(j)
    }
    // the inActRouter in port zero comes from GLB, so we can disable the GroupIO's inAct port zero
    io.dataPath.cgDataPath.iRIO(i).inIOs.head <> DontCare
    // the inActRouter out port zero connected to peCluster, so disable the GroupIO's inAct port zero
    io.dataPath.cgDataPath.iRIO(i).outIOs.head <> DontCare
    // inner connections
    routerCluster.dataPath.routerData.iRIO(i).inIOs.head <> glbCluster.dataPath.inActIO(i).outIOs
    peCluster.dataPath.inActIO(i) <> routerCluster.dataPath.routerData.iRIO(i).outIOs.head
  }
  // weight
  require(io.dataPath.cgDataPath.wRIO.head.inIOs.length == 2, "the number of ports in weight router should be 2 " +
    "or you should correct the code")
  for (i <- 0 until weightRouterNum) {
    // input: port 0 from GLB Cluster, 1 from neighboring weight router
    // output: port 0 to PE Cluster, 1 to neighboring weight router
    // top connections
    glbCluster.dataPath.weightIO(i).inIOs <> io.dataPath.glbDataPath.weightIO(i).inIOs
    io.dataPath.glbDataPath.weightIO(i).outIOs <> DontCare // FIXME: when need, transfer it to vertical neighborhood Group
    routerCluster.dataPath.routerData.wRIO(i).inIOs.last <> io.dataPath.cgDataPath.wRIO(i).inIOs.last
    // the corresponding port of weightRouter connected GLB, so disable it
    io.dataPath.cgDataPath.wRIO(i).inIOs.head <> DontCare
    io.dataPath.cgDataPath.wRIO(i).outIOs.last <> routerCluster.dataPath.routerData.wRIO(i).outIOs.last
    // the corresponding port of weightRouter connected GLB, so disable it
    io.dataPath.cgDataPath.wRIO(i).outIOs.head <> DontCare
    // inner connections
    routerCluster.dataPath.routerData.wRIO(i).inIOs.head <> glbCluster.dataPath.weightIO(i).outIOs
    peCluster.dataPath.weightIO(i) <> routerCluster.dataPath.routerData.wRIO(i).outIOs.head
  }
  // partial sum
  require(io.dataPath.cgDataPath.pSumRIO.head.inIOs.length == 3, "the number of ports in partial sum router should be 3 or you should correct the code")
  for (i <- 0 until pSumRouterNum) {
    // 0 for PE Cluster, 1 for GLB Cluster, 2 for vertical
    // top connections
    when (doCalReg) {
      io.dataPath.glbDataPath.pSumIO(i).outIOs <> DontCare
      glbCluster.dataPath.pSumIO(i).outIOs.ready := false.B
    } .otherwise {
      io.dataPath.glbDataPath.pSumIO(i).outIOs <> glbCluster.dataPath.pSumIO(i).outIOs
    }
    routerCluster.dataPath.routerData.pSumRIO(i).inIOs.last <> io.dataPath.cgDataPath.pSumRIO(i).inIOs.last
    // only need the final one to receive data outside, other two comes from inner
    io.dataPath.cgDataPath.pSumRIO(i).inIOs.take(2).foreach(_ <> DontCare)
    io.dataPath.cgDataPath.pSumRIO(i).outIOs.last <> routerCluster.dataPath.routerData.pSumRIO(i).outIOs.last
    // only need the final one to receive data outside, other two comes from inner
    io.dataPath.cgDataPath.pSumRIO(i).outIOs.take(2).foreach(_ <> DontCare)
    // pSumData from or to vertical PE Arrays
    io.dataPath.pSumDataVerticalIOs.outIOs(i) <> peCluster.dataPath.pSumIO.outIOs(i)
    peCluster.dataPath.pSumDataFromSouthernIO(i) <> io.dataPath.pSumDataVerticalIOs.inIOs(i)
    // inner connections
    // peCluster.dataPath.pSumIO.outIOs(i) transfer partial sum not only to outside cluster group, but also to router
    routerCluster.dataPath.routerData.pSumRIO(i).inIOs.head <> peCluster.dataPath.pSumIO.outIOs(i)
    // peCluster.dataPath.pSumIO.inIOs(i) receive data directly from pSumRouter
    peCluster.dataPath.pSumIO.inIOs(i) <> routerCluster.dataPath.routerData.pSumRIO(i).outIOs.head
    when (doCalReg) {
      routerCluster.dataPath.routerData.pSumRIO(i).inIOs(1) <> glbCluster.dataPath.pSumIO(i).outIOs
      glbCluster.dataPath.pSumIO(i).inIOs <> routerCluster.dataPath.routerData.pSumRIO(i).outIOs(1)
      io.dataPath.glbDataPath.pSumIO(i).inIOs.ready := false.B
    } .otherwise {
      routerCluster.dataPath.routerData.pSumRIO(i).inIOs(1) <> DontCare
      glbCluster.dataPath.pSumIO(i).inIOs <> io.dataPath.glbDataPath.pSumIO(i).inIOs
      routerCluster.dataPath.routerData.pSumRIO(i).outIOs(1).ready := false.B
    }
  }
  // configIO
  glbCluster.ctrlPath.configIOs <> DontCare // FIXME
  peCluster.ctrlPath.configIOs <> DontCare
  // writeOrRead
  glbCluster.ctrlPath.pSumIO.writeOrRead := DontCare // FIXME
  glbCluster.ctrlPath.inActIO.writeOrRead := DontCare
  // doEn
  glbCluster.ctrlPath.pSumIO.doEn := DontCare // FIXME
  glbCluster.ctrlPath.inActIO.doEn := DontCare
  peCluster.ctrlPath.doEn := DontCare
}