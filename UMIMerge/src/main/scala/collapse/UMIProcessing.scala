package collapse

import java.io._

import aligner.{BasicAligner, NeedlemanWunsch}
import utils.Utils
import reads.{ForwardReadOrientation, RankedReadContainer, ReverseReadOrientation, SequencingRead, SingleRankedReadContainer}

import scala.collection.mutable
import scala.io._

/**
 * created by aaronmck on 2/13/14
 *
 * Copyright (c) 2014, aaronmck
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 2.  Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
 *
 */
case class Config(inputFileReads1: Option[File] = None,
                  inputFileReads2: Option[File] = None,
                  outputFastq1: Option[File] = None,
                  outputFastq2: Option[File] = None,
                  outputUMIStats: Option[File] = None,
                  umiLength: Int = 10,
                  umiStartPos: Int = 0,
                  primersEachEnd: Option[File] = None,
                  samplename: String = "TEST",
                  minimumUMIReads: Int = 10,
                  minimumSurvivingUMIReads: Int = 6,
                  umiInForwardRead: Boolean = true,
                  downsampleSize: Int = 40,
                  primersToCheck: String = "BOTH",
                  primerMismatches: Int = 7,
                  processSingleReads: Boolean = false)



object UMIProcessing extends App {
  val NOTAREALFILENAME = "/0192348102jr10234712930h8j19p0hjf129-348h512935"
  // please don't make a file with this name
  val NOTAREALFILE = new File(NOTAREALFILENAME)

  // parse the command line arguments
  val parser = new scopt.OptionParser[Config]("UMIMerge") {
    head("UMIMerge", "1.0")

    // *********************************** Inputs *******************************************************
    opt[File]("inputFileReads1") required() valueName ("<file>") action { (x, c) => c.copy(inputFileReads1 = Some(x)) } text ("first read file ")
    opt[File]("inputFileReads2") valueName ("<file>") action { (x, c) => c.copy(inputFileReads2 = Some(x)) } text ("second reads file")
    opt[File]("outputFastq1") required() valueName ("<file>") action { (x, c) => c.copy(outputFastq1 = Some(x)) } text ("the output stats file")
    opt[File]("outputFastq2") valueName ("<file>") action { (x, c) => c.copy(outputFastq2 = Some(x)) } text ("the output stats file")
    opt[File]("umiCounts") required() valueName ("<file>") action { (x, c) => c.copy(outputUMIStats = Some(x)) } text ("the counts of each UMI in the data")
    opt[File]("primersEachEnd") required() valueName ("<file>") action { (x, c) => c.copy(primersEachEnd = Some(x)) } text ("the file containing the amplicon primers requred to be present, one per line, two lines total")
    opt[Int]("minimumUMIReads") action { (x, c) => c.copy(minimumUMIReads = x) } text ("the minimum number of reads that each UMI should have to be considered signal and not noise")
    opt[Int]("minimumSurvivingUMIReads") action { (x, c) => c.copy(minimumSurvivingUMIReads = x) } text ("the minimum number of reads that each UMI should have post filtering")
    opt[Int]("downsampleSize") action { (x, c) => c.copy(downsampleSize = x) } text ("the maximum number of top-reads we'll store for any UMI")
    opt[Int]("primerMismatches") action { (x, c) => c.copy(primerMismatches = x) } text ("how many mismatches are allowed in primer regions")
    opt[String]("primersToCheck") action { (x, c) => c.copy(primersToCheck = x) } text ("should we check both primers, or just one? Or none?")
    opt[Boolean]("processSingleReads") action { (x, c) => c.copy(processSingleReads = x) } text ("process single reads instead of paired end")


    opt[Int]("umiStart") required() action { (x, c) => c.copy(umiStartPos = x) } text ("the start position, zero based, of our UMIs")
    opt[Int]("umiLength") required() action { (x, c) => c.copy(umiLength = x) } text ("the length of our UMIs")
    opt[String]("samplename") required() action { (x, c) => c.copy(samplename = x) } text ("the sample name of this run")


    // some general command-line setup stuff
    note("processes reads with UMIs into merged reads\n")
    help("help") text ("prints the usage information you see here")
  }

  // *********************************** Run *******************************************************
  // run the actual read processing -- our argument parser found all of the parameters it needed
  parser.parse(args, Config()) map {
    config: Config => {
      if (config.outputFastq2.isDefined && !config.processSingleReads)
        umiAnalysis(config)
      else
        umiAnalysisSingleEnd(config)
    }
  } getOrElse {
    println("Unable to parse the command line arguments you passed in, please check that your parameters are correct")
  }



  /**
   * given UMIed reads, process per UMI, merging reads and calling events
    *
    * @param config our config object
   */
  def umiAnalysis(config: Config): Unit = {
    // our output files
    val outputFastq1File = new PrintWriter(config.outputFastq1.get)
    val outputFastq2File = new PrintWriter(config.outputFastq2.get)

    // setup clustered input of the fastq files
    // ------------------------------------------------------------------------------------------
    val forwardReads = Source.fromInputStream(Utils.gis(config.inputFileReads1.get.getAbsolutePath)).getLines().grouped(4)
    val reverseReads = Source.fromInputStream(Utils.gis(config.inputFileReads2.get.getAbsolutePath)).getLines().grouped(4)

    val primers = Source.fromFile(config.primersEachEnd.get.getAbsolutePath).getLines().map { line => line }.toList
    if (primers.length != 2)
      throw new IllegalStateException("You should only provide a primer file with two primers")

    // our containers for forward and reverse reads
    var umiReads = new mutable.HashMap[String, RankedReadContainer]()

    var tooFewReadsUMI = 0
    var downsampledUMI = 0
    var justRightUMI = 0

    // --------------------------------------------------------------------------------
    // process the reads into bins of UMIs, keep fwd/rev reads together
    // --------------------------------------------------------------------------------
    print("Reading in sequences and parsing out UMIs (one dot per 100K reads, carets at 1M): ")

    var readsProcessed = 0
    forwardReads foreach { fGroup => {
      val rGroup = reverseReads.next()

      // for the forward read the UMI start position is used literally,
      // for the reverse read (when start is negitive) we go from the end of the read backwards that much.
      // for instance to allow UMIs to start at the zero'th base on the reverse, they would have provided -1 as the input
      var umi: Option[String] = None

      if (config.umiStartPos >= 0) {
        umi = Some(fGroup(1).slice(config.umiStartPos, config.umiStartPos + config.umiLength))

        val readNoUMI = fGroup(1).slice(0, config.umiStartPos) + fGroup(1).slice(config.umiStartPos + config.umiLength, fGroup(1).length)
        val qualNoUMI = fGroup(3).slice(0, config.umiStartPos) + fGroup(3).slice(config.umiStartPos + config.umiLength, fGroup(3).length)

        val (containsForward, containsReverse) = config.primersToCheck match {
          case "BOTH" => Utils.containsBothPrimerByAlignment(readNoUMI,rGroup(1), primers(0), primers(1),config.primerMismatches)
          case "FORWARD" => (Utils.containsFWDPrimerByAlignment(readNoUMI,primers(0),config.primerMismatches),true)
          case "REVERSE" => (true,Utils.containsREVCompPrimerByAlignment(rGroup(1),primers(1),config.primerMismatches))
          case _ => throw new IllegalArgumentException("Unable to parse primer configuration state: " + config.primerMismatches)
        }

        if (!(umiReads contains umi.get))
          umiReads(umi.get) = new RankedReadContainer(umi.get, config.downsampleSize)

        val fwd = SequencingRead(fGroup(0), readNoUMI, qualNoUMI, ForwardReadOrientation, umi.get)
        val rev = SequencingRead(rGroup(0), rGroup(1), rGroup(3), ReverseReadOrientation, umi.get)

        umiReads(umi.get).addRead(fwd, containsForward, rev, containsReverse)
      }
      else {
        val umiStartPos = math.abs(config.umiStartPos).toInt - 1
        umi = Some(rGroup(1).slice(umiStartPos, umiStartPos + config.umiLength))

        val readTwoNoUMI = rGroup(1).slice(0, umiStartPos) + rGroup(1).slice(umiStartPos + config.umiLength, rGroup(1).length)
        val qualNoUMI = rGroup(3).slice(0, umiStartPos) + rGroup(3).slice(umiStartPos + config.umiLength, rGroup(3).length)

        val (containsForward, containsReverse) = config.primersToCheck match {
          case "BOTH" => Utils.containsBothPrimerByAlignment(fGroup(1),readTwoNoUMI, primers(0), primers(1),config.primerMismatches)
          case "FORWARD" => (Utils.containsFWDPrimerByAlignment(fGroup(1),primers(0),config.primerMismatches),true)
          case "REVERSE" => (true,Utils.containsREVCompPrimerByAlignment(readTwoNoUMI,primers(1),config.primerMismatches))
          case _ => throw new IllegalArgumentException("Unable to parse primer configuration state: " + config.primerMismatches)
        }

        if (!(umiReads contains umi.get))
          umiReads(umi.get) = new RankedReadContainer(umi.get,config.downsampleSize)

        val fwd = SequencingRead(fGroup(0), fGroup(1), fGroup(3), ForwardReadOrientation, umi.get)
        val rev = SequencingRead(rGroup(0), readTwoNoUMI, qualNoUMI, ReverseReadOrientation, umi.get)

        umiReads(umi.get).addRead(fwd, containsForward, rev, containsReverse)
      }

      readsProcessed += 1
      if (readsProcessed % 100000 == 0)
        print(".")
      if (readsProcessed % 1000000 == 0)
        print("^")
    }
    }

    var passingUMI = 0
    var totalWithUMI = 0

    // --------------------------------------------------------------------------------
    // for each UMI -- process the collection of reads
    // --------------------------------------------------------------------------------
    var index = 1
    val outputUMIData : Option[PrintWriter] = if (config.outputUMIStats.get.getAbsolutePath != NOTAREALFILE.getAbsolutePath)
      Some(new PrintWriter(config.outputUMIStats.get.getAbsolutePath)) else None

    if (outputUMIData.isDefined)
      outputUMIData.get.write("umi\ttotalCount\tpassCount\tmissingPrimer1\tmissingPrimer2\tsequence\n")


    println("\n\nTotal UMIs to process: " + umiReads.size)
    umiReads.foreach { case (umi, reads) => {
      val greaterThanMinimumReads = reads.size >= config.minimumUMIReads

      if (greaterThanMinimumReads) {
        val (fwdReads, revReads) = reads.toPairedFWDREV()

        val res = UMIMerger.mergeTogether(umi,
          fwdReads,
          revReads,
          reads.totalPassedReads,
          reads.totalPassedReads,
          outputFastq1File,
          outputFastq2File,
          primers,
          config.samplename,
          config.minimumSurvivingUMIReads,
          index,
          BasicAligner)

        outputUMIData.get.write(umi + "\t" + reads.totalReads + "\t" +
          reads.totalPassedReads + "\t" + reads.noPrimer1 + "\t" +
          reads.noPrimer2 + "\t" + res.read1SurvivingCount + "\t" + res.read2SurvivingCount + "\t" + res.read1Consensus + ";" + res.read2Consensus + "\n")

      } else {
        outputUMIData.get.write(umi + "\t" + reads.totalReads + "\t" +
          reads.totalPassedReads + "\t" + reads.noPrimer1 + "\t" +
          "NA\tNA\t" + reads.noPrimer2 + "\tNOTENOUGHREADS\n")
      }


      if (index % 1000 == 0) {
        println("INFO: Processed " + index + " umis so far")
      }
      index += 1
    }
    }

    if (outputUMIData.isDefined)
      outputUMIData.get.close()
    outputFastq1File.close()
    outputFastq2File.close()
  }


  /**
    * given UMIed reads, process per UMI, merging reads and calling events
    *
    * @param config our config object
    */
  def umiAnalysisSingleEnd(config: Config): Unit = {
    // our output files
    val outputFastq1File = new PrintWriter(config.outputFastq1.get)

    // setup clustered input of the fastq files
    // ------------------------------------------------------------------------------------------
    val forwardReads = Source.fromInputStream(Utils.gis(config.inputFileReads1.get.getAbsolutePath)).getLines().grouped(4)

    val primers = Source.fromFile(config.primersEachEnd.get.getAbsolutePath).getLines().map { line => line }.toList
    if (primers.length != 2)
      throw new IllegalStateException("You should only provide a primer file with two primers")

    // our containers for forward and reverse reads
    var umiReads = new mutable.HashMap[String, SingleRankedReadContainer]()

    var tooFewReadsUMI = 0
    var downsampledUMI = 0
    var justRightUMI = 0

    // --------------------------------------------------------------------------------
    // process the reads into bins of UMIs
    // --------------------------------------------------------------------------------
    print("Reading in sequences and parsing out UMIs (one dot per 100K reads, carets at 1M): ")
    var readsProcessed = 0
    forwardReads foreach { fGroup => {

      // for the forward read the UMI start position is used literally,
      // for the reverse read (when start is negitive) we go from the end of the read backwards that much. To
      // allow UMIs to start at the zero'th base on the reverse, we say the first base is one, second is 2, etc.
      var umi: Option[String] = None

      if (config.umiStartPos >= 0) {
        umi = Some(fGroup(1).slice(config.umiStartPos, config.umiStartPos + config.umiLength))

        val readNoUMI = fGroup(1).slice(0, config.umiStartPos) + fGroup(1).slice(config.umiStartPos + config.umiLength, fGroup(1).length)
        val qualNoUMI = fGroup(3).slice(0, config.umiStartPos) + fGroup(3).slice(config.umiStartPos + config.umiLength, fGroup(3).length)

        val containsForward = if (config.primersToCheck == "BOTH" || config.primersToCheck == "FORWARD") {
          //println("Compare " + readNoUMI.slice(0, primers(0).length) + " to " + primers(0))
          Utils.editDistance(readNoUMI.slice(0, primers(0).length), primers(0)) <= config.primerMismatches
        }
        else true

        if (!(umiReads contains umi.get))
          umiReads(umi.get) = new SingleRankedReadContainer(umi.get, config.downsampleSize)

        val fwd = SequencingRead(fGroup(0), readNoUMI, qualNoUMI, ForwardReadOrientation, umi.get)

        umiReads(umi.get).addRead(fwd, containsForward)
      }
      else {
        throw new IllegalStateException("Unable to pull UMIs from reverse read in single-end mode")
      }

      readsProcessed += 1
      if (readsProcessed % 100000 == 0)
        print(".")
      if (readsProcessed % 1000000 == 0)
        print("^")
    }
    }

    var passingUMI = 0
    var totalWithUMI = 0

    // --------------------------------------------------------------------------------
    // for each UMI -- process the collection of reads
    // --------------------------------------------------------------------------------
    var index = 1
    val outputUMIData : Option[PrintWriter] = if (config.outputUMIStats.get.getAbsolutePath != NOTAREALFILE.getAbsolutePath)
      Some(new PrintWriter(config.outputUMIStats.get.getAbsolutePath)) else None

    if (outputUMIData.isDefined)
      outputUMIData.get.write("umi\ttotalCount\tpassCount\tmissingPrimer1\tmissingPrimer2\n")


    println("\n\nTotal UMIs to process: " + umiReads.size)
    umiReads.foreach { case (umi, reads) => {

      val greaterThanMinimumReads = reads.size >= config.minimumUMIReads

      if (greaterThanMinimumReads) {
        val (fwdReads, revReads) = reads.toPairedFWDREV()

        val res = UMIMerger.mergeTogetherSingleReads(umi,
          fwdReads,
          reads.totalPassedReads,
          reads.totalPassedReads,
          outputFastq1File,
          primers,
          config.samplename,
          config.minimumSurvivingUMIReads,
          index,
          BasicAligner)

        outputUMIData.get.write(umi + "\t" + reads.totalReads + "\t" +
          reads.totalPassedReads + "\t" + reads.noPrimer1 + "\t" +
          reads.noPrimer2 + res.readSurvivingCount + "\tNA\t" + "\t" + res + "\n")

      } else {
        outputUMIData.get.write(umi + "\t" + reads.totalReads + "\t" +
          reads.totalPassedReads + "\t" + reads.noPrimer1 + "\t" +
          "NA\tNA\t" + reads.noPrimer2 + "\tNOTENOUGHREADS\n")
      }

      if (index % 1000 == 0) {
        println("INFO: Processed " + index + " umis so far")
      }
      index += 1
    }
    }

    if (outputUMIData.isDefined)
      outputUMIData.get.close()
    outputFastq1File.close()
  }
}