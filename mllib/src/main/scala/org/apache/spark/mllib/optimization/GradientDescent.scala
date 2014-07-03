/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.optimization

import scala.collection.mutable.ArrayBuffer

import breeze.linalg.{DenseVector => BDV}

import org.apache.spark.annotation.{Experimental, DeveloperApi}
import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.{Vectors, Vector}
import java.util.Random

/**
 * Class used to solve an optimization problem using Gradient Descent.
 * @param gradient Gradient function to be used.
 * @param updater Updater to be used to update weights after every iteration.
 */
class GradientDescent private[mllib] (private var gradient: Gradient, private var updater: Updater)
  extends Optimizer with Logging {

  private var stepSize: Double = 1.0
  private var numIterations: Int = 100
  private var regParam: Double = 0.0
  private var miniBatchFraction: Double = 1.0

  /**
   * Set the initial step size of SGD for the first step. Default 1.0.
   * In subsequent steps, the step size will decrease with stepSize/sqrt(t)
   */
  def setStepSize(step: Double): this.type = {
    this.stepSize = step
    this
  }

  /**
   * :: Experimental ::
   * Set fraction of data to be used for each SGD iteration.
   * Default 1.0 (corresponding to deterministic/classical gradient descent)
   */
  @Experimental
  def setMiniBatchFraction(fraction: Double): this.type = {
    this.miniBatchFraction = fraction
    this
  }

  /**
   * Set the number of iterations for SGD. Default 100.
   */
  def setNumIterations(iters: Int): this.type = {
    this.numIterations = iters
    this
  }

  /**
   * Set the regularization parameter. Default 0.0.
   */
  def setRegParam(regParam: Double): this.type = {
    this.regParam = regParam
    this
  }

  /**
   * Set the gradient function (of the loss function of one single data example)
   * to be used for SGD.
   */
  def setGradient(gradient: Gradient): this.type = {
    this.gradient = gradient
    this
  }


  /**
   * Set the updater function to actually perform a gradient step in a given direction.
   * The updater is responsible to perform the update from the regularization term as well,
   * and therefore determines what kind or regularization is used, if any.
   */
  def setUpdater(updater: Updater): this.type = {
    this.updater = updater
    this
  }

  /**
   * :: DeveloperApi ::
   * Runs gradient descent on the given training data.
   * @param data training data
   * @param initialWeights initial weights
   * @return solution vector
   */
  @DeveloperApi
  def optimize(data: RDD[(Double, Vector)], initialWeights: Vector): Vector = {
    val (weights, _) = if (miniBatchFraction > 0.0) {
      // run minibatch
      GradientDescent.runMiniBatchSGD(
        data,
        gradient,
        updater,
        stepSize,
        numIterations,
        regParam,
        miniBatchFraction,
        initialWeights)
    } else {
      // run per instance learning
      GradientDescent.runGradientDescent(
        data,
        gradient,
        updater,
        stepSize,
        numIterations,
        regParam,
        initialWeights)
    }
    weights
  }

}

/**
 * :: DeveloperApi ::
 * Top-level method to run gradient descent.
 */
@DeveloperApi
object GradientDescent extends Logging {
  /**
   * Run stochastic gradient descent (SGD) in parallel using mini batches.
   * In each iteration, we sample a subset (fraction miniBatchFraction) of the total data
   * in order to compute a gradient estimate.
   * Sampling, and averaging the subgradients over this subset is performed using one standard
   * spark map-reduce in each iteration.
   *
   * @param data - Input data for SGD. RDD of the set of data examples, each of
   *               the form (label, [feature values]).
   * @param gradient - Gradient object (used to compute the gradient of the loss function of
   *                   one single data example)
   * @param updater - Updater function to actually perform a gradient step in a given direction.
   * @param stepSize - initial step size for the first step
   * @param numIterations - number of iterations that SGD should be run.
   * @param regParam - regularization parameter
   * @param miniBatchFraction - fraction of the input data set that should be used for
   *                            one iteration of SGD. Default value 1.0.
   *
   * @return A tuple containing two elements. The first element is a column matrix containing
   *         weights for every feature, and the second element is an array containing the
   *         stochastic loss computed for every iteration.
   */
  def runMiniBatchSGD(
      data: RDD[(Double, Vector)],
      gradient: Gradient,
      updater: Updater,
      stepSize: Double,
      numIterations: Int,
      regParam: Double,
      miniBatchFraction: Double,
      initialWeights: Vector): (Vector, Array[Double]) = {

    val stochasticLossHistory = new ArrayBuffer[Double](numIterations)

    val numExamples = data.count()
    val miniBatchSize = numExamples * miniBatchFraction

    // Initialize weights as a column vector
    var weights = Vectors.dense(initialWeights.toArray)

    /**
     * For the first iteration, the regVal will be initialized as sum of weight squares
     * if it's L2 updater; for L1 updater, the same logic is followed.
     */
    var regVal = updater.compute(
      weights, Vectors.dense(new Array[Double](weights.size)), 0, 1, regParam)._2

    for (i <- 1 to numIterations) {
      // Sample a subset (fraction miniBatchFraction) of the total data
      // compute and sum up the subgradients on this subset (this is one map-reduce)
      val (gradientSum, lossSum) = data.sample(false, miniBatchFraction, 42 + i)
        .aggregate((BDV.zeros[Double](weights.size), 0.0))(
          seqOp = (c, v) => (c, v) match { case ((grad, loss), (label, features)) =>
            val l = gradient.compute(features, label, weights, Vectors.fromBreeze(grad))
            (grad, loss + l)
          },
          combOp = (c1, c2) => (c1, c2) match { case ((grad1, loss1), (grad2, loss2)) =>
            (grad1 += grad2, loss1 + loss2)
          })

      /**
       * NOTE(Xinghao): lossSum is computed using the weights from the previous iteration
       * and regVal is the regularization value computed in the previous iteration as well.
       */
      stochasticLossHistory.append(lossSum / miniBatchSize + regVal)
      val update = updater.compute(
        weights, Vectors.fromBreeze(gradientSum / miniBatchSize), stepSize, i, regParam)
      weights = update._1
      regVal = update._2
    }

    logInfo("GradientDescent.runMiniBatchSGD finished. Last 10 stochastic losses %s".format(
      stochasticLossHistory.takeRight(10).mkString(", ")))

    (weights, stochasticLossHistory.toArray)
  }

  /**
   * Run gradient descent in parallel, for each partition, scan data sequentially.
   * In each iteration, we perform gradient descent on each partition separately,
   * weights are updated sequentially for each data instance inside a partition; afterwards,
   * the weights are avg across partitions before the next iteration. There is one standard
   * spark map-reduce in each iteration.
   *
   * @param data - Input data for SGD. RDD of the set of data examples, each of
   *               the form (label, [feature values]).
   * @param gradient - Gradient object (used to compute the gradient of the loss function of
   *                   one single data example)
   * @param updater - Updater function to actually perform a gradient step in a given direction,
   *                  it must be an instance of LazyUpdater, otherwise an exception is thrown.
   * @param stepSize - initial step size for the first step
   * @param numIterations - number of iterations that SGD should be run.
   * @param regParam - regularization parameter
   * @return A tuple containing two elements. The first element is a column matrix containing
   *         weights for every feature, and the second element is an array containing the
   *         stochastic loss computed for every iteration.
   */
  def runGradientDescent(
      data: RDD[(Double, Vector)],
      gradient: Gradient,
      updater: Updater,
      stepSize: Double,
      numIterations: Int,
      regParam: Double,
      initialWeights: Vector): (Vector, Array[Double]) = {

    val lazyUpdater = if (!updater.isInstanceOf[LazyUpdater]) {
      throw new IllegalArgumentException("Updater should be lazy")
    } else {
      updater.asInstanceOf[LazyUpdater]
    }

    val stochasticLossHistory = new ArrayBuffer[Double](numIterations)

    val numPartitions = data.partitions.length

    // Initialize weights as a column vector
    var weights = Vectors.dense(initialWeights.toArray)

    val iters = new Array[Int](numPartitions)
    for (i <- 1 to numIterations) {
      logDebug("GradientDescent.runGradientDescent iteration %d".format(i))

      def descentPartition(
          index: Int,
          iter: Iterator[(Double, Vector)]): Iterator[(Vector, Double, Seq[(Int, Int)])] = {

        val localUpdadter = lazyUpdater.clone()
        var localWeights = Vectors.dense(weights.toArray)
        val startIter = iters(index)
        var localIter = startIter
        var loss = 0.0
        while (iter.hasNext) {
          val v = iter.next()
          localIter += 1
          val (grad, l) = gradient.compute(v._2, v._1, localWeights,
            localUpdadter.weightShrinkage, localUpdadter.weightTruncation)
          loss += l

          localWeights = localUpdadter.compute(localWeights, grad, stepSize,
            localIter, regParam)._1
        }

        logDebug("GradientDescent.runGradientDescent Updater " +
          " weight shrinkage %f, truncation %f before catch up".format(
            localUpdadter.weightShrinkage, localUpdadter.weightTruncation))
        val finalWeights = localUpdadter.applyLazyRegularization(localWeights)
        loss /= (localIter - startIter)
        Iterator((finalWeights, loss, Seq((index, localIter))))
      }

      def mergeWeights(
          m1: (Vector, Double, Seq[(Int, Int)]),
          m2: (Vector, Double, Seq[(Int, Int)])): (Vector, Double, Seq[(Int, Int)]) = {
        val sumWeights = Vectors.fromBreeze(m1._1.toBreeze + m2._1.toBreeze)
        (sumWeights, m1._2 + m2._2, m1._3 ++ m2._3)
      }
      val (newWeights, lossSum, iterIndexedSeq) =
        data.mapPartitionsWithIndex(descentPartition, true)
          .reduce(mergeWeights)
      weights = Vectors.fromBreeze(newWeights.toBreeze :*= 1.0 / iterIndexedSeq.size)
      val avgLoss = lossSum / iterIndexedSeq.size
      val regVal = lazyUpdater.computeRegularizationPenalty(weights, regParam)
      stochasticLossHistory.append(avgLoss + regVal)
      iterIndexedSeq.foreach { kv: (Int, Int) =>
        iters(kv._1) = kv._2
      }
      logDebug("GradientDescent.runGradientDescent iteration %d finish with loss %f, regVal %f"
        .format(i, avgLoss, regVal))
    }

    logInfo("GradientDescent.runGradientDescent finished. Last 10 stochastic losses %s".format(
      stochasticLossHistory.takeRight(10).mkString(", ")))

    (weights, stochasticLossHistory.toArray)
  }

}
