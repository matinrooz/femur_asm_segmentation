package fitting
import breeze.linalg.{DenseMatrix, DenseVector}
import scalismo.common.{PointId, UnstructuredPointsDomain}
import scalismo.common.interpolation.BSplineImageInterpolator3D
import scalismo.geometry.{EuclideanVector, Landmark, Point, SquareMatrix, _3D}
import scalismo.io.{ActiveShapeModelIO, ImageIO, LandmarkIO, MeshIO, StatisticalModelIO}
import scalismo.mesh.TriangleMesh
import scalismo.image.DiscreteImage
import scalismo.sampling.algorithms.MetropolisHastings
import scalismo.sampling.evaluators.ProductEvaluator
import scalismo.sampling.loggers.AcceptRejectLogger
import scalismo.sampling.proposals.{MetropolisFilterProposal, MixtureProposal}
import scalismo.statisticalmodel.{MultivariateNormalDistribution, PointDistributionModel, StatisticalMeshModel}
import scalismo.ui.api.ScalismoUI
import scalismo.utils.Random
import fitting.evaluators.{ASMEvaluator, CachedEvaluator, ClosestPointEvaluator, CorrespondenceEvaluator, ImageEvaluator, PriorEvaluator}
import fitting.logger.Logger
import fitting.parameters.{Parameters, Sample}
import fitting.proposals.{NoiseUpdateProposal, RotationUpdateProposal, ShapeUpdateProposal, TranslationUpdateProposal}
import plots.DiagnosticPlots
import scalismo.statisticalmodel.asm.{ActiveShapeModel, FittingConfiguration, ModelTransformations, NormalDirectionSearchPointSampler, PreprocessedImage}
import scalismo.sampling.proposals.MixtureProposal.ProposalGeneratorWithTransition
import scalismo.sampling.DistributionEvaluator

import java.awt.Color
import java.io.File

object Main {
  // This helper method is a hack to work around a bug in Scalismo, namely that the ASM class returns
  // a StatisticalMeshModel instead of a PointDistribution model. This method automagically does the
  // conversion.
  implicit def ssmToPDM(meshModel : StatisticalMeshModel) : PointDistributionModel[_3D, TriangleMesh] = {
    PointDistributionModel(meshModel.gp)
  }


  val ui = ScalismoUI()

  val modelGroup = ui.createGroup("model")
  val imgGroup = ui.createGroup("image")


  def computeCenterOfMass(mesh : TriangleMesh[_3D]) : Point[_3D] = {
    val normFactor = 1.0 / mesh.pointSet.numberOfPoints
    mesh.pointSet.points.foldLeft(Point(0, 0, 0))((sum, point) => sum + point.toVector * normFactor)
  }


  def computeMean(model: PointDistributionModel[_3D, UnstructuredPointsDomain], id: PointId, samples: IndexedSeq[Sample]): Point[_3D] = {
    var mean = EuclideanVector(0, 0, 0)
    for (sample <- samples) yield {
      val modelInstance = model.instance(sample.parameters.modelCoefficients)
      val pointForInstance = modelInstance.transform(sample.poseTransformation).pointSet.point(id)
      mean += pointForInstance.toVector
    }
    (mean * 1.0 / samples.size).toPoint
  }

  def computeCovarianceFromSamples(model: PointDistributionModel[_3D, UnstructuredPointsDomain],
                                   id: PointId,
                                   mean: Point[_3D],
                                   samples: IndexedSeq[Sample]): SquareMatrix[_3D]= {
    var cov = SquareMatrix.zeros[_3D]
    for (sample <- samples) yield {
      val modelInstance = model.instance(sample.parameters.modelCoefficients)
      val pointForInstance = modelInstance.transform(sample.poseTransformation).pointSet.point(id)
      val v = pointForInstance - mean
      cov += v.outer(v)
    }
    cov * (1.0 / samples.size)
  }
  // Main function uses 3 Markov Chains to fit the femur.


  def fitModel(asm : ActiveShapeModel,
               correspondencePoints : Seq[(PointId, Point[_3D])], contourPoints: Seq[Point[_3D]],image : DiscreteImage[_3D, Short], useStoredPoseFit : Boolean = false)
              (implicit rng : scalismo.utils.Random) : Unit = {

    // Load and display model.
    val shapeModel = asm.statisticalModel

    val modelView = ui.show(modelGroup, shapeModel, "volume model")
    val gpTrans = modelView.shapeModelTransformationView.shapeTransformationView
    val poseTrans = modelView.shapeModelTransformationView.poseTransformationView

    // Uncertainty corresponding to each landmark.
    val uncertaintyScore = IndexedSeq[Int](10, 30, 20, 5, 10, 20, 10, 30)

    // Function to get the corresponding uncertainty in form of a multivariate normal distribution
    def getUncertainty(index: Int): MultivariateNormalDistribution = {
      val landmarkVariance = uncertaintyScore(index)
      MultivariateNormalDistribution(
        DenseVector.zeros[Double](3),
        DenseMatrix.eye[Double](3) * landmarkVariance.toDouble
      )
    }




    // Set correspondences.
    val correspondences = correspondencePoints.toIndexedSeq.zipWithIndex.map { case (modelIdWithTargetPoint, index) =>
      val (modelId, targetPoint) = modelIdWithTargetPoint
      (modelId, targetPoint, getUncertainty(index))
    }.toSeq


    // Define likelihood, prior and posterior for the first two Markov Chains.
       val closestPointEvaluator = ClosestPointEvaluator(shapeModel, contourPoints)
    val likelihoodEvaluator = CorrespondenceEvaluator(shapeModel, correspondences=correspondencePoints)
    val priorEvaluator = PriorEvaluator(shapeModel)
    val asmEvaluator = ASMEvaluator(asm, asm.preprocessor.apply(image.map(_.toFloat)))
    val posteriorEvaluator = ProductEvaluator(closestPointEvaluator,priorEvaluator, likelihoodEvaluator/*,asmEvaluator*/)

    val resultGroup = ui.createGroup("result")

    def MarkovChain(generator: ProposalGeneratorWithTransition[Sample], evaluator: DistributionEvaluator[Sample], initialSample: Sample, drop: Int, take: Int): Sample = {

      val mh = MetropolisHastings(generator, evaluator)
      val logger = new Logger()
      val mhIterator = mh.iterator(initialSample, logger)

      val samplingIterator = for ((sample, iteration) <- mhIterator.zipWithIndex) yield {
        if (iteration % 500 == 0) {
          println("iteration " + iteration)
          gpTrans.coefficients = sample.parameters.modelCoefficients
          poseTrans.transformation = sample.poseTransformation
        }
        sample
      }

      val samples = samplingIterator.drop(drop).take(take).toIndexedSeq
      DiagnosticPlots.tracePlot(samples, posteriorEvaluator, new File("plots/traceplot.png"))
      DiagnosticPlots.pairs(samples, new File("plots/pairs.png") )

      println(logger.acceptanceRatios())

      samples.maxBy(posteriorEvaluator.logValue)
    }

    // Generator for First chain (rotation and translation)
    val rotationUpdateProposal1 = RotationUpdateProposal(0.0001)
    val translationUpdateProposal1 = TranslationUpdateProposal(0.1)
    val firstgenerator = MixtureProposal.fromProposalsWithTransition(
      (0.2, rotationUpdateProposal1),
      (0.8, translationUpdateProposal1)
    )

    // Set initial parameters and initial sample.
    val initialParameters = Parameters(EuclideanVector(0, 0, 0), (0.0, 0.0, 0.0), DenseVector.zeros[Double](shapeModel.rank), 25.0)
    val initialSample = Sample("initial sample",
      initialParameters,
      computeCenterOfMass(shapeModel.mean))

    // Run the first chain to obtain an updated best sample
    val firstBestSample = MarkovChain(firstgenerator, posteriorEvaluator, initialSample, 1000, 7000)
    val firstBestInstance = shapeModel.instance(firstBestSample.parameters.modelCoefficients).transform(firstBestSample.poseTransformation)

    val firstBestInstanceView = ui.show(resultGroup, firstBestInstance, "First Fit")
    firstBestInstanceView.color = java.awt.Color.CYAN

    // Second chain (mainly shape, some rotation and translation)
    val shapeUpdateProposal2 = ShapeUpdateProposal(shapeModel.rank, 0.1)
    val rotationUpdateProposal2 = RotationUpdateProposal(0.01)
    val translationUpdateProposal2 = TranslationUpdateProposal(0.5)
    val secondgenerator = MixtureProposal.fromProposalsWithTransition(
      (0.6, shapeUpdateProposal2),
      (0.2, rotationUpdateProposal2),
      (0.2, translationUpdateProposal2)
    )

    // Use previous best sample as initial sample and run the second Markov Chain
    val firstSample = Sample("initial sample", firstBestSample.parameters, computeCenterOfMass(firstBestInstance))
    val secondBestSample = MarkovChain(secondgenerator, posteriorEvaluator, firstSample, 1000, 10000)

    // Display temporary result
    val secondBestInstance = shapeModel.instance(secondBestSample.parameters.modelCoefficients).transform(secondBestSample.poseTransformation)
    val secondBestInstanceView = ui.show(resultGroup, secondBestInstance, "Second Fit")
    secondBestInstanceView.color = java.awt.Color.PINK

    // Third chain (using Active Shape Model for likelihood)
    val floatImage = image.map(value => value.toFloat)
    val preprocessedImage = asm.preprocessor(floatImage)


    // Re-define prior, likelihood and posterior
    //  val intensityEvaluator = CachedEvaluator(ImageEvaluator(asm, preprocessedImage))
    val intensityEvaluator = CachedEvaluator(ImageEvaluator(asm, preprocessedImage, shapeModel))
    val tempEvaluator = ProductEvaluator(priorEvaluator, likelihoodEvaluator)
    val posteriorIntensityEvaluator = ProductEvaluator(tempEvaluator, intensityEvaluator)

    // Set generator
    val shapeUpdateProposal3 = ShapeUpdateProposal(shapeModel.rank, 0.1)

    val rotationUpdateProposal31 = RotationUpdateProposal(0.01)
    val rotationUpdateProposal32 = RotationUpdateProposal(0.1)

    val translationUpdateProposal31 = TranslationUpdateProposal(1.0)
    val translationUpdateProposal32 = TranslationUpdateProposal(2.0)
    val translationUpdateProposal33 = TranslationUpdateProposal(5.0)

    val thirdgenerator = MixtureProposal.fromProposalsWithTransition(

      (0.35, shapeUpdateProposal3),
      (0.25, shapeUpdateProposal3),

      (0.15, rotationUpdateProposal31),
      (0.25, rotationUpdateProposal32),

      (0.5, translationUpdateProposal31),
      (0.6, translationUpdateProposal31),
      (0.5, translationUpdateProposal32),
      (0.5, translationUpdateProposal33)

    )

    // Use previous best sample as initial sample and run the final Markov Chain
    val SecondSample = Sample("initial", secondBestSample.parameters, computeCenterOfMass(secondBestInstance))
    val finalBestSample = MarkovChain(thirdgenerator, posteriorIntensityEvaluator, SecondSample, 1000, 11000)



    // Display results
    val finalBestInstance = shapeModel.instance(finalBestSample.parameters.modelCoefficients).transform(finalBestSample.poseTransformation)
    val finalBestInstanceView = ui.show(resultGroup, finalBestInstance, " Final Best Fit")
    finalBestInstanceView.color = java.awt.Color.PINK


    // Write results for validation
    MeshIO.writeSTL(finalBestInstance, new File("datasets/validationset/fitting-results/9.stl"))


    // Display landmarks for visual validation of the result
    val lmGroup = ui.createGroup("landmarks")
    correspondencePoints.zipWithIndex.foreach { case (cp, index) =>
      val lmTargetView = ui.show(lmGroup, Landmark(s"lm_target $index", cp._2), "lm")
      lmTargetView.color = java.awt.Color.GREEN
      val lmReferenceView = ui.show(lmGroup, Landmark(s"lm_reference $index", shapeModel.referenceMesh.pointSet.point(cp._1)), "lm")
      lmReferenceView.color = java.awt.Color.YELLOW
    }
    ui.show(lmGroup, shapeModel.referenceMesh, "reference")
  }


  def main(args : Array[String]) : Unit = {
    scalismo.initialize()
    implicit val rng = scalismo.utils.Random(42)

    // Load active shape model and transform it into a statistical model
    val activeShapeModel = ActiveShapeModelIO.readActiveShapeModel(new java.io.File("datasets/asm/femur-asm.h5")).get
    val shapeModel = activeShapeModel.statisticalModel

    // Load landmarks and create correspondence
    val modelLandmarks = LandmarkIO.readLandmarksJson[_3D](new java.io.File("datasets/asm/femur-asm-lms.json")).get

    val targetLandmarks =  LandmarkIO.readLandmarksJson[_3D](new java.io.File("datasets/validationset/landmarks/9.json")).get
    val correspondencePoints = modelLandmarks
      .map(lm => lm.point) // extract point from landmark
      .map(point => shapeModel.referenceMesh.pointSet.findClosestPoint(point).id) // find point id
      .zip(targetLandmarks.map(lm => lm.point)) // pair it with targetLandmarkPoint

      val contourLandmarks = LandmarkIO.readLandmarksJson[_3D](
         new java.io.File("datasets/validationset/landmarks/9.json")
       ).get
       val contourPoints = contourLandmarks.map(lm => lm.point)


  //   val contourLandmarkViews = ui.show(imgGroup, contourLandmarks, "contourPoints")
     val contourLandmarkViews = ui.show(imgGroup, contourLandmarks, "contourPoints")
    contourLandmarkViews.map(cLmView => cLmView.color = Color.BLUE)

    // Load and display target image
    val discreteTargetImage = ImageIO.read3DScalarImageAsType[Short](new java.io.File("datasets/testset/9.nii")).get
    val targetImage = discreteTargetImage.interpolate(BSplineImageInterpolator3D[Short](3))
    ui.show(imgGroup, discreteTargetImage, "image")

    // Fit the model to the image
    fitModel(activeShapeModel, correspondencePoints,contourPoints, discreteTargetImage)

  }
}
