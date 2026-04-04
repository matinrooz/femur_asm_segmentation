package fitting.evaluators

import fitting.parameters.Sample
import scalismo.sampling.DistributionEvaluator
import scalismo.statisticalmodel.StatisticalMeshModel
import scalismo.statisticalmodel.asm.{ActiveShapeModel, PreprocessedImage}

case class  ImageEvaluator(asm: ActiveShapeModel,
    preprocessedImage: PreprocessedImage, shapeModel: StatisticalMeshModel)
  extends DistributionEvaluator[Sample] {
    override def logValue(sample: Sample): Double = {


      val currModelInstance = shapeModel.instance(sample.parameters.modelCoefficients).transform(sample.poseTransformation)
      val ids = asm.profiles.ids

      val likelihoods = for (id <- ids) yield {
        val profile = asm.profiles(id)
        val profilePointOnMesh = currModelInstance.pointSet.point(profile.pointId)
        asm.featureExtractor(preprocessedImage, profilePointOnMesh, currModelInstance, profile.pointId) match {
          case Some(p) => profile.distribution.logpdf(p)
          case None => 1e-50
        }
      }
      likelihoods.sum

    }
  }
