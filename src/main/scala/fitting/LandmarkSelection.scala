package fitting

import scalismo.geometry._
import scalismo.ui.api._
import scalismo.io.{ActiveShapeModelIO, ImageIO, LandmarkIO}


object LandmarkSelection {

  scalismo.initialize()
  val ui = ScalismoUI()


  /*
  *  Load the model and each image for landmark selection
  *  After selecting landmarks from the Scalismo GUI, saved as a .json file
  *  5 landmarks were selected
  *
  * */
  def main(args: Array[String]): Unit = {

    // load the model
    val asm = ActiveShapeModelIO.readActiveShapeModel(new java.io.File("datasets/asm/femur-asm.h5")).get
    val modelGroup = ui.createGroup("modelGroup")
    val modelView = ui.show(modelGroup, asm.statisticalModel.mean, "shapeModel")

    // display selected reference landmarks from the model
    val modelLms = LandmarkIO.readLandmarksJson[_3D](new java.io.File("datasets/asm/femur-asm-lms.json")).get
    val modelLmViews = ui.show(modelGroup, modelLms, "modelLandmarks")
    modelLmViews.foreach(lmView => lmView.color = java.awt.Color.BLUE)


   // val contour = LandmarkIO.readLandmarksJson[_3D](new java.io.File("datasets/validationset/annotations/4.json")).get
   // val modelCnViews = ui.show(modelGroup, contour, "Annotations")
  //  modelCnViews.foreach(lmView => lmView.color = java.awt.Color.CYAN)

    // load image and manually select landmarks from the Scalismo GUI
    val image = ImageIO.read3DScalarImage[Short](new java.io.File("datasets/testset/37.nii")).get.map(_.toFloat)
    val targetGroup = ui.createGroup("target")
    val imageView = ui.show(targetGroup, image, "targetImage")
  }
}
