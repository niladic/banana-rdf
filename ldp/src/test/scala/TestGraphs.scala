package org.w3.banana.ldp

import java.security.interfaces.RSAPublicKey
import java.security.KeyPairGenerator
import org.w3.banana._
import java.net.{URL => jURL}
import java.util.Date
import scala.concurrent.Future
import org.w3.play.api.libs.ws.ResponseHeaders
import org.w3.banana

/**
 * Build up a set of Graphs with representing some realistic scenarios that can then be used
 * across a number of different tests
 * @tparam Rdf
 */
trait TestGraphs[Rdf<:RDF] {
  implicit val ops: RDFOps[Rdf]
  implicit val recordBinder: binder.RecordBinder[Rdf]

  import ops._
  import syntax._
  import diesel._

  val certbinder = new CertBinder()
  import certbinder._


  implicit def toUri(url: jURL): Rdf#URI = URI(url.toString)

  val wac = WebACLPrefix[Rdf]
  val foaf = FOAFPrefix[Rdf]
  val rdf = RDFPrefix[Rdf]
  val rdfs = RDFSPrefix[Rdf]
  val ldp = LDPPrefix[Rdf]
  val cert = CertPrefix[Rdf]

  val keyGen = KeyPairGenerator.getInstance("RSA");
  val henryRsaKey: RSAPublicKey = { keyGen.initialize(768);  keyGen.genKeyPair().getPublic().asInstanceOf[RSAPublicKey] }
  val bertailsRsaKey: RSAPublicKey = { keyGen.initialize(512);  keyGen.genKeyPair().getPublic().asInstanceOf[RSAPublicKey] }

  val timbl = URI("http://www.w3.org/People/Berners-Lee/card#i")
  val timblCard = URI("http://www.w3.org/People/Berners-Lee/card")
  val timblGraph: Rdf#Graph = (
    URI("#i") -- foaf.name ->- "Tim Berners-Lee"
    ).graph

  val henryCard = URI("http://bblfish.net/people/henry/card")
  val henry =  URI(henryCard.toString+"#me")
  val henryGraph : Rdf#Graph = (
    URI("#me") -- cert.key ->- henryRsaKey
      -- foaf.name ->- "Henry"
    ).graph

  val henryColl = URI("http://bblfish.net/people/henry/")
  val henryCollGraph: Rdf#Graph = henryColl.a(ldp.Container).graph

  val henryCardAcl = URI("http://bblfish.net/people/henry/card;wac")
  val henryCardAclGraph: Rdf#Graph = (
    bnode("t1")
      -- wac.accessTo ->- henryCard
      -- wac.agent ->- henry
      -- wac.mode ->- wac.Read
      -- wac.mode ->- wac.Write
    ).graph union (
    bnode("t2")
      -- wac.accessTo ->- henryCard
      -- wac.agentClass ->- foaf.Agent
      -- wac.mode ->- wac.Read
    ).graph

  val henryFoaf = URI("http://bblfish.net/people/henry/foaf")
  lazy val henryFoafGraph: Rdf#Graph = (
   henry -- foaf.knows ->- timbl
         -- foaf.knows ->- bertails
    ).graph

  val henryFoafWac = URI("http://bblfish.net/people/henry/foaf;wac")
  lazy val henryFoafWacGraph : Rdf#Graph = (
    bnode() -- wac.accessTo ->- henryFoaf
      -- wac.agentClass ->- tpacGroup
      -- wac.mode ->- wac.Read
    ).graph

  val tpacGroupDoc = URI("http://www.w3.org/2005/Incubator/webid/tpac/group")
  val tpacGroup = URI("http://www.w3.org/2005/Incubator/webid/tpac/group#socWeb")
  lazy val tpacGroupGraphPG = (
    URI("#socWeb").a(foaf.Group)
      -- foaf.member ->- henry
      -- foaf.member ->- bertails
      -- foaf.member ->- timbl
    )
  lazy val tpacGroupGraph: Rdf#Graph = tpacGroupGraphPG.graph
  lazy val tpacGroupLDPR = LinkedDataResource(tpacGroupDoc,tpacGroupGraphPG)

  val groupACLForRegexResource: Rdf#Graph = (
    bnode("t1")
      -- wac.accessToClass ->- ( bnode("t2") -- wac.regex ->- "http://bblfish.net/blog/.*" )
      -- wac.agentClass ->- ( URI("http://bblfish.net/blog/editing/.meta#a1") -- foaf.member ->- henry )
      -- wac.mode ->- wac.Read
      -- wac.mode ->- wac.Write
    ).graph



  //
  // local resources
  //

  val bertailsContainer =    URI("http://example.com/foo/bertails/")
  val bertailsContainerAcl = URI("http://example.com/foo/bertails/;acl")
  val bertails =             URI("http://example.com/foo/bertails/card#me")
  val bertailsCard =         URI("http://example.com/foo/bertails/card")
  val bertailsCardAcl =      URI("http://example.com/foo/bertails/card;acl")
  val bertailsFoaf =         URI("http://example.com/foo/bertails/foaf")
  val bertailsFoafAcl =      URI("http://example.com/foo/bertails/foaf;acl")


  val bertailsCardGraph: Rdf#Graph = (
    URI("#me")
      -- foaf.name ->- "Alexandre".lang("fr")
      -- foaf.title ->- "Mr"
      -- cert.key ->- bertailsRsaKey
    ).graph

  val bertailsCardAclGraph: Rdf#Graph = (
    bnode("t1")
      -- wac.accessTo ->- bertailsCard
      -- wac.agentClass ->- foaf.Agent
      -- wac.mode ->- wac.Read
    ).graph  union (
    URI("") -- wac.include ->- URI(";acl")
    ).graph


  val bertailsContainerAclGraph: Rdf#Graph = (
    bnode("t2")
      -- wac.accessToClass ->- ( bnode -- wac.regex ->- (bertailsContainer.toString+".*") )
      -- wac.agent ->- bertails
      -- wac.mode ->- wac.Write
      -- wac.mode ->- wac.Read
    ).graph

  val bertailsFoafGraph: Rdf#Graph = (
    URI("card#me") -- foaf.knows ->- henry
    ).graph

  val bertailsFoafAclGraph: Rdf#Graph = (
    URI("") -- wac.include ->- URI(";acl")
    ).graph


  object testFetcher extends WebClient[Rdf] {
    case class TestLDPR(location: Rdf#URI, graph: Rdf#Graph, metaGraph: Rdf#Graph=Graph.empty)(implicit val ops: RDFOps[Rdf]) extends LDPR[Rdf] {
      def updated = Some(new Date())

      /**
       * location of initial ACL for this resource
       **/
      def acl = Some{
        if (location.toString.endsWith(";wac")) location
        else ops.URI(location.toString+";wac")
      }

      //move all the metadata to this, and have the other functions
      def meta = PointedGraph(location,metaGraph)
    }

    import collection.mutable.{Map,SynchronizedMap}
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    class SynMap extends collection.mutable.HashMap[Rdf#URI,Rdf#Graph] with collection.mutable.SynchronizedMap[Rdf#URI,Rdf#Graph]
    lazy val synMap = new SynMap() ++= Seq(
      henryColl -> henryCollGraph,
      henryCard -> henryGraph,
      henryCardAcl -> henryCardAclGraph,
      tpacGroupDoc-> tpacGroupGraph,
      timblCard -> timblGraph,
      henryFoafWac -> henryFoafWacGraph
    )

    def get(url: Rdf#URI): Future[NamedResource[Rdf]] = {
      synMap.get(url).map{g=>futuRes(url,g)}.getOrElse(
        Future.failed(RemoteException("resource does not exist",
          ResponseHeaders(404, collection.immutable.Map()))
        )
      )
    }

    def futuRes(r: Rdf#URI, graph: Rdf#Graph): Future[TestLDPR] = {
      Future.successful(TestLDPR(r, graph.resolveAgainst(r)))   //todo: should this really not be a relative graph?
    }

    def post[S](url: Rdf#URI, slug: Option[String], graph: Rdf#Graph, syntax: banana.Syntax[S])
               (implicit writer: Writer[Rdf#Graph, S]): Future[Rdf#URI] = {
      val collectionURL = url.fragmentLess
      if (!collectionURL.toString.endsWith("/")) {
        Future.failed(RemoteException("cannot create resource",ResponseHeaders(405,collection.immutable.Map())))
      } else {
        synMap.get(collectionURL).map { gr =>
          if ((PointedGraph(collectionURL, gr) / rdf.typ).exists(_.pointer == ldp.Container)) {
            val newURI = URI(collectionURL.toString+slug.getOrElse(counter.addAndGet(1)))
            synMap.put(collectionURL,gr union (collectionURL -- rdfs.member ->- newURI).graph)
            Future.successful(newURI)
          } else {
            Future.failed(RemoteException("Post not on container",ResponseHeaders(405,collection.immutable.Map())))
          }
        }.getOrElse(Future.failed(RemoteException("resource does not exist",ResponseHeaders(404,collection.immutable.Map()))))
      }
    }

    def delete(url: Rdf#URI): Future[Unit] = {
      val old = synMap.remove(url.fragmentLess)
      old.fold{
        Future.failed[Unit](
          RemoteException("cannot delete non existent resource",
            ResponseHeaders(404, collection.immutable.Map()))
        )
      }{x=>
        Future.successful[Unit](())
      }
    }
  }


}