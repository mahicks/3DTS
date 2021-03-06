import com.thesamet.spatial._
import org.saddle._

object StructureContext {

  def featureFromSlidingWindow(
      cif: CIFContents,
      windowSize: Int,
      radius: Double): List[((PdbChain, PdbResidueNumber),
                             Seq[(PdbChain, PdbResidueNumber)])] = {
    assert(windowSize % 2 == 1, "even windowSize")
    val atomsByResidue = cif.assembly.atoms
      .groupBy(x => (x._2, x._3))
      .map(x => x._1 -> x._2.map(_._1))

    type T1 = (Atom, PdbChain, PdbResidueNumber, FakePdbChain)

    implicit val dimensionalOrderingForAtom: DimensionalOrdering[T1] =
      new DimensionalOrdering[T1] {
        val dimensions = 3

        def compareProjection(d: Int)(x: T1, y: T1) =
          x._1.coord.raw(d).compareTo(y._1.coord.raw(d))
      }

    def liftIntoFakeT1(v: Vec[Double]) =
      (Atom(v, -1, "", "", "", ""),
       PdbChain("?"),
       PdbResidueNumber(-1, None),
       FakePdbChain("?"))

    val kdtree: KDTree[T1] = KDTree(cif.assembly.atoms: _*)

    val jump = windowSize / 2

    cif.aminoAcidSequence.flatMap {
      case (chain, aa) =>
        val sorted = aa.sortBy(_._2).toVector
        val length = sorted.length
        val halfWindow = windowSize / 2
        val start = 0
        val end = length - 1
        start to end by jump map { center =>
          val left = math.max(center - halfWindow, 0)
          val right = math.min(center + halfWindow, end)
          val centerResidueOfFeature: PdbResidueNumber = sorted(center)._2
          val featureAtoms: List[Atom] = (left to right flatMap { idx =>
            val residue = sorted(idx)._2
            atomsByResidue.get(chain -> residue).toVector.flatten
          } toList)

          val expandedAtoms2: Vector[T1] =
            featureAtoms.flatMap { fa =>
              val bbXTop = fa.coord.raw(0) + radius
              val bbXBot = fa.coord.raw(0) - radius
              val bbYTop = fa.coord.raw(1) + radius
              val bbYBot = fa.coord.raw(1) - radius
              val bbZTop = fa.coord.raw(2) + radius
              val bbZBot = fa.coord.raw(2) - radius
              val queryRegion = Region
                .from(liftIntoFakeT1(Vec(bbXBot, 0d, 0d)), 0)
                .to(liftIntoFakeT1(Vec(bbXTop, 0d, 0d)), 0)
                .from(liftIntoFakeT1(Vec(0d, bbYBot, 0d)), 1)
                .to(liftIntoFakeT1(Vec(0d, bbYTop, 0d)), 1)
                .from(liftIntoFakeT1(Vec(0d, 0d, bbZBot)), 2)
                .to(liftIntoFakeT1(Vec(0d, 0d, bbZTop)), 2)

              kdtree
                .regionQuery(queryRegion)
                .filter(atomWithPdb => fa.within(radius, atomWithPdb._1.coord))
            }.distinct.toVector

          val expandedResidues = expandedAtoms2.map(x => (x._2, x._3)).distinct

          (chain -> centerResidueOfFeature) -> expandedResidues

        }
    }.toList

  }

  def featureFromUniprotFeatureSegmentation(
      pdbId: PdbId,
      cif: CIFContents,
      features: Seq[(PdbChain, UniprotFeatureName, Set[PdbResidueNumber])],
      radius: Double)
    : List[((PdbChain, UniprotFeatureName, PdbResidueNumber, PdbResidueNumber),
            Seq[(PdbChain, PdbResidueNumber)])] = {

    val atomsByResidue = cif.assembly.atoms
      .groupBy(x => (x._2, x._3))
      .map(x => x._1 -> x._2.map(_._1))

    type T1 = (Atom, PdbChain, PdbResidueNumber, FakePdbChain)

    implicit val dimensionalOrderingForAtom: DimensionalOrdering[T1] =
      new DimensionalOrdering[T1] {
        val dimensions = 3

        def compareProjection(d: Int)(x: T1, y: T1) =
          x._1.coord.raw(d).compareTo(y._1.coord.raw(d))
      }

    def liftIntoFakeT1(v: Vec[Double]) =
      (Atom(v, -1, "", "", "", ""),
       PdbChain("?"),
       PdbResidueNumber(-1, None),
       FakePdbChain("?"))

    val kdtree: KDTree[T1] = KDTree(cif.assembly.atoms: _*)

    features
      .filter(x => x._3.size > 0 && x._3.size < 200)
      .map {
        case (chain, featureName, residuesInFeature) =>
          println(pdbId, chain, featureName, residuesInFeature.size)
          val featureAtoms: List[Atom] = (residuesInFeature flatMap {
            residue =>
              atomsByResidue.get(chain -> residue).toVector.flatten
          } toList)

          val expandedAtoms2: Vector[T1] =
            featureAtoms.flatMap { fa =>
              val bbXTop = fa.coord.raw(0) + radius
              val bbXBot = fa.coord.raw(0) - radius
              val bbYTop = fa.coord.raw(1) + radius
              val bbYBot = fa.coord.raw(1) - radius
              val bbZTop = fa.coord.raw(2) + radius
              val bbZBot = fa.coord.raw(2) - radius
              val queryRegion = Region
                .from(liftIntoFakeT1(Vec(bbXBot, 0d, 0d)), 0)
                .to(liftIntoFakeT1(Vec(bbXTop, 0d, 0d)), 0)
                .from(liftIntoFakeT1(Vec(0d, bbYBot, 0d)), 1)
                .to(liftIntoFakeT1(Vec(0d, bbYTop, 0d)), 1)
                .from(liftIntoFakeT1(Vec(0d, 0d, bbZBot)), 2)
                .to(liftIntoFakeT1(Vec(0d, 0d, bbZTop)), 2)

              kdtree
                .regionQuery(queryRegion)
                .filter(atomWithPdb => fa.within(radius, atomWithPdb._1.coord))
            }.distinct.toVector

          val expandedResidues = expandedAtoms2.map(x => (x._2, x._3)).distinct

          (chain,
           featureName,
           residuesInFeature.toSeq.sorted.min,
           residuesInFeature.toSeq.sorted.max) -> expandedResidues

      }
      .toList

  }

}
