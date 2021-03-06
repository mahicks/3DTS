import fileutils._
import stringsplit._

import IOHelpers._
import MathHelpers._
import Model._

import tasks._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import java.net._
import com.typesafe.config._

class TaskRunner(implicit ts: TaskSystemComponents) {

  def run(config: Config) = {

    val uniprotKbOriginal = importFile(config.getString("uniprotKb"))

    val gencodeGtf = importFile(config.getString("gencodeGTF"))

    val gencodeTranscripts = importFile(config.getString("gencodeTranscripts"))

    val gencodeMetadataXrefUniprot = importFile(
      config.getString("gencodeMetadataXrefUniprot"))

    val gnomadExomeCoverageFile: SharedFile = importFile(
      config.getString("gnomadExomeCoverage"))

    val gnomadGenomeCoverageFile: SharedFile = importFile(
      config.getString("gnomadGenomeCoverage"))

    val convertedGnomadGenome = {
      val gnomadGenome = importFile(config.getString("gnomadGenome"))
      ConvertGnomad2HLI.task(GnomadData(gnomadGenome))(
        CPUMemoryRequest(1, 5000))
    }
    val convertedGnomadExome = {
      val gnomadExome = importFile(config.getString("gnomadExome"))
      ConvertGnomad2HLI.task(GnomadData(gnomadExome))(
        CPUMemoryRequest(1, 5000))
    }

    val gnomadExomeCoverage = ConvertGenomeCoverage.task(
      gnomadExomeCoverageFile -> 123136)(CPUMemoryRequest(1, 5000))
    val gnomadGenomeCoverage = ConvertGenomeCoverage.task(
      gnomadGenomeCoverageFile -> 15496)(CPUMemoryRequest(1, 5000))

    val filteredGnomadExomeCoverage = gnomadExomeCoverage.flatMap { g =>
      FilterCoverage.task(FilterCoverageInput(g, gencodeGtf))(
        CPUMemoryRequest(1, 5000))
    }

    val filteredGnomadGenomeCoverage = gnomadGenomeCoverage.flatMap { g =>
      FilterCoverage.task(FilterCoverageInput(g, gencodeGtf))(
        CPUMemoryRequest(1, 5000))
    }

    val filteredGnomadGenome =
      convertedGnomadGenome.flatMap { gnomadGenome =>
        FilterGnomad.task(
          FilterGnomadInput(gnomadGenome, "gnomad", gencodeGtf))(
          CPUMemoryRequest(1, 5000))
      }

    val filteredGnomadExome = convertedGnomadExome.flatMap { gnomadExome =>
      FilterGnomad.task(FilterGnomadInput(gnomadExome, "gnomad", gencodeGtf))(
        CPUMemoryRequest(1, 5000))
    }

    val uniprotgencodemap = gencodeUniprot.task(
      GencodeUniprotInput(gencodeGtf,
                          gencodeMetadataXrefUniprot,
                          gencodeTranscripts,
                          uniprotKbOriginal))(CPUMemoryRequest(1, 30000))

    val variationsJoined = uniprotgencodemap.flatMap { uniprotgencodemap =>
      filteredGnomadGenome.flatMap { filteredGnomadGenome =>
        filteredGnomadExome.flatMap { filteredGnomadExome =>
          filteredGnomadExomeCoverage.flatMap { exomeCov =>
            filteredGnomadGenomeCoverage.flatMap { genomeCov =>
              joinVariations.task(
                JoinVariationsInput(filteredGnomadExome.f,
                                    filteredGnomadGenome.f,
                                    uniprotgencodemap,
                                    exomeCov,
                                    genomeCov,
                                    gencodeGtf))(CPUMemoryRequest(1, 60000))
            }
          }
        }
      }
    }

    val uniprotpdbmap = uniprotgencodemap.flatMap { uniprotgencodemap =>
      UniProtPdb.task(
        UniProtPdbFullInput(uniprotKbOriginal, uniprotgencodemap))(
        CPUMemoryRequest(1, 1000))
    }

    val cppdb = uniprotgencodemap.flatMap { uniprotgencodemap =>
      uniprotpdbmap.flatMap { uniprotpdbmap =>
        JoinCPWithPdb.task(
          JoinCPWithPdbInput(
            uniprotgencodemap,
            uniprotpdbmap.tables.map(_._2).sortBy(_.sf.name)))(
          CPUMemoryRequest((1, 3), 60000))
      }
    }

    val cppdbindex = cppdb.flatMap { cppdb =>
      JoinCPWithPdb.indexCpPdb(cppdb)(CPUMemoryRequest(1, 60000))
    }

    val simplecppdb = cppdb.flatMap { cppdb =>
      ProjectCpPdb.task(cppdb)(CPUMemoryRequest(1, 5000))
    }

    val mappableUniprotIds = uniprotgencodemap.flatMap { uniprotgencodemap =>
      MappableUniprot.task(uniprotgencodemap)(CPUMemoryRequest(1, 1000))
    }

    val cifs = mappableUniprotIds.flatMap { mappedUniprotIds =>
      Assembly2Pdb.fetchCif(
        Assembly2PdbInput(uniprotKbOriginal, mappedUniprotIds))(
        CPUMemoryRequest((1, 8), 3000))
    }

    val assemblies = cifs.flatMap { cifs =>
      Assembly2Pdb.assembly(cifs)(CPUMemoryRequest((1, 16), 3000))
    }

    val scores = {
      val radius = 5d

      val features = uniprotpdbmap.flatMap { uniprotpdbmap =>
        cifs.flatMap { cifs =>
          StructuralContext.taskfromFeatures(
            StructuralContextFromFeaturesInput(
              cifs = cifs.cifFiles,
              mappedUniprotFeatures = uniprotpdbmap.tables.map(_._3).toSet,
              radius = radius))(CPUMemoryRequest((1, 12), 1000))
        }
      }

      val feature2cp = cppdb.flatMap { cppdb =>
        features.flatMap { features =>
          Feature2CPSecond.task(
            Feature2CPSecondInput(featureContext = features, cppdb = cppdb))(
            CPUMemoryRequest(1, 60000))
        }
      }

      val depletionScores = feature2cp.flatMap { feature2cp =>
        variationsJoined.flatMap { variationsJoined =>
          depletion3d.task(
            Depletion3dInputFull(
              locusDataFile = variationsJoined,
              featureFile = feature2cp
            ))(CPUMemoryRequest(1, 1))
        }
      }

      val scores2pdb = depletionScores.flatMap { scores =>
        features.flatMap { features =>
          Depletion2Pdb.task(
            Depletion2PdbInput(
              scores.js,
              features
            ))(CPUMemoryRequest(1, 10000))
        }
      }

      val indexedScores = scores2pdb.flatMap { scores =>
        Depletion2Pdb.indexByPdbId(scores)(CPUMemoryRequest(1, 20000))
      }

     // var server = if (startServer) indexedScores.flatMap { index =>
     //   cppdbindex.flatMap { cppdb =>
     //     Server.start(8080, index, cppdb)
     //   }
     // } else Future.successful(())

      List(indexedScores)
    }

    Future.sequence(
      List(cppdbindex,
           uniprotgencodemap,
           cppdb,
           simplecppdb,
           uniprotpdbmap,
           variationsJoined,
           uniprotgencodemap,
           assemblies) ++ scores)

  }
}

object await {
  def apply[T](f: Future[T]) = Await.result(f, 60 minutes)
}

object importFile {
  def apply(s: String)(implicit ce: TaskSystemComponents) =
    if (s.startsWith("http") || s.startsWith("s3"))
      await(SharedFile(tasks.util.Uri(s)))
    else await(SharedFile(new java.io.File(s), new java.io.File(s).getName))
}

object ProteinDepletion extends App {

  System.setProperty("org.apache.commons.logging.Log",
                     "org.apache.commons.logging.impl.NoOpLog");

  //val startServer = args(0).toBoolean

  val config = ConfigFactory.load()

  withTaskSystem { implicit ts =>
    val result =
      Await.result(new TaskRunner().run(config),
                   atMost = 168 hours)
    //if (startServer) {
    //  println("Pipeline done. Blocking indefinitely to keep the server up.")
    //  while (true) {
    //    Thread.sleep(Long.MaxValue)
    //  }
    //}
  }

}
