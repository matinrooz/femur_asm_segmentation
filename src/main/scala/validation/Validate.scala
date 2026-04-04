package validation

import scalismo.io.MeshIO
import scalismo.mesh.MeshMetrics
import scalismo.ui.api.ScalismoUI

object Validate {

  def main(args : Array[String]) : Unit = {
    scalismo.initialize()
    val validationFolder = new java.io.File("./datasets/validationset/")
    val fittingFolder = new java.io.File(validationFolder, "fitting-results")
    val segmentationFolder = new java.io.File(validationFolder, "segmentations")

    val fittingResultFiles = fittingFolder.listFiles()
      .filter(f => f.getName.endsWith(".stl") || f.getName.endsWith(".ply") || f.getName.endsWith(".vtk"))
      .toIndexedSeq

    case class ValidationResult(name : String, avgDist : Double, hausdorffDist : Double)

    val results = for (file <- fittingResultFiles) yield {

      // Assumption, fitting result and segmentation have the same filename
      val fittedMesh = MeshIO.readMesh(file).get
      val segmentationFile = new java.io.File(segmentationFolder, file.getName)
      val segmentation = MeshIO.readMesh(segmentationFile).get
      val avgDist = MeshMetrics.avgDistance(fittedMesh, segmentation)
      val hausdorffDist = MeshMetrics.hausdorffDistance(fittedMesh, segmentation)
      ValidationResult(file.getName(), avgDist, hausdorffDist)
    }

    results.map(println)
  }
}
