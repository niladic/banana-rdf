package org.w3.banana.ldp

import scala.language.reflectiveCalls

import org.w3.banana._
import java.nio.file._
import scala.concurrent._
import akka.actor._
import akka.util._
import akka.pattern.ask
import org.slf4j.LoggerFactory
import java.util.Date
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input.El
import java.io.{IOException, OutputStream}
import scala.util.Try
import java.net.{URI => jURI}
import scala.Some
import scalaz.\/-
import scalaz.-\/

trait RActor extends Actor with akka.actor.ActorLogging {

  def returnErrors[A,B](pf: Receive): Receive = new PartialFunction[Any,Unit] {
    //interestingly it seems we can't catch an error here! If we do, we have to return a true or a false
    // and whatever we choose it could have bad sideffects. What happens if the isDefinedAt throws an exception?
      def isDefinedAt(x: Any): Boolean = pf.isDefinedAt(x)
      def apply(a: Any): Unit = try {
        log.info(s"received $a");
        pf.apply(a)
      } catch {
        case e: Exception => sender ! akka.actor.Status.Failure(e)
      }
    }

  def local(u: jURI, base: jURI): Option[String] = {
    val res = if ((!u.isAbsolute ) || (u.getScheme == base.getScheme && u.getHost == base.getHost && u.getPort == base.getPort)) {
      if (u.getPath.startsWith(base.getPath)) {
        val res = u.getPath.substring(base.getPath.size)
        val sections = res.split('/')
        val fileName = sections.last
        var idot= fileName.indexOf('.')
        if (idot>=0) sections.update(sections.length-1,fileName.substring(0,idot))
        Option(sections.mkString("/"))
      } else None
    } else None
    res
  }

}

class LocalSetup {
  def aclPath(path: String) = {
    val p = path+".acl"
    p
  }

  def isAclPath(path: String) = {
    val a =path.endsWith(".acl")
    a
  }
}

// A resource on the server ( Resource is already taken. )
// note:
// There can be named and unamed resources, as when a POST creates a
// resource that is not given a name... so this should probably extend a more abstract resource
trait NamedResource[Rdf<:RDF] extends Meta[Rdf] {
   def location: Rdf#URI
}

/**
 * Metadata about a resource
 *   This may be thought to be so generic that a graph representation would do,
 *   but it is very likely to be very limited set of properties and so to be
 *   better done in form methods for efficiency reasons.
 */
trait Meta[Rdf <: RDF] {
  def location: Rdf#URI

  //move all the metadata to this, and have the other functions
  def meta: PointedGraph[Rdf]

  def ops: RDFOps[Rdf]

  def updated: Option[Date]
  /*
 * A resource should ideally be versioned, so any change would get a version URI
 * ( but this is probably something that should be on a MetaData trait
 **/
  def version: Option[Rdf#URI] = None

  /**
   * location of initial ACL for this resource
   **/
  def acl: Option[Rdf#URI]

  //other metadata candidates:
  // - owner
  // - etag
  //

}


/**
 * A binary resource does not get direct semantic interpretation.
 * It has a mime type. One can write bytes to it, to replace its content, or one
 * can read its content.
 * @tparam Rdf
 */
trait BinaryResource[Rdf<:RDF] extends NamedResource[Rdf]  {

  def size: Option[Long]

  def mime: MimeType

  // creates a new BinaryResource, with new time stamp, etc...
  def write:  Iteratee[Array[Byte], BinaryResource[Rdf]]
  def reader(chunkSize: Int): Enumerator[Array[Byte]]
}

/*
 * And LDPC is currently defined in the LDP ontology as a subclass of an LDPR.
 * This LDPR class is more what we are thinking as the non binary non LDPCs...
 * - an LDPS must subscribe to the death of its LDPC
 */

trait LDPR[Rdf <: RDF] extends NamedResource[Rdf] with LinkedDataResource[Rdf]  {
  import org.w3.banana.syntax._
  def location: Rdf#URI

  def graph: Rdf#Graph // all uris are relative to location

  /* the graph such that all URIs are relative to $location */
  def relativeGraph(implicit ops: RDFOps[Rdf]): Rdf#Graph  = graph.relativize(location)

  def resource: PointedGraph[Rdf] = PointedGraph(location,graph)

}



case class OperationNotSupported(msg: String) extends Exception(msg)

//todo: the way of finding the meta data should not be set here, but in the implementation
trait LocalNamedResource[Rdf<:RDF] extends NamedResource[Rdf] {
  lazy val acl: Option[Rdf#URI]= Some{
    var loc=location.toString
    if (loc.endsWith(".acl")) location
    else ops.URI(loc+".acl")
  }
}


case class LocalBinaryR[Rdf<:RDF](path: Path, location: Rdf#URI)
                                   (implicit val ops: RDFOps[Rdf])
  extends BinaryResource[Rdf] with LocalNamedResource[Rdf] {
  import org.w3.banana.syntax.URISyntax.uriW
  import ops._

  def meta = PointedGraph(location,Graph.empty)  //todo: need to build it correctly


  // also should be on a metadata trait, since all resources have update times
  def updated = Try { new Date(Files.getLastModifiedTime(path).toMillis) }.toOption

  val size = Try { Files.size(path) }.toOption

  def mime = ???

  // creates a new BinaryResource, with new time stamp, etc...
  //here I can just write to the file, as that should be a very quick operation, which even if it blocks,
  //should be extreemly fast server side.  Iteratee
  def write: Iteratee[Array[Byte], LocalBinaryR[Rdf] ] = {
    val tmpfile = Files.createTempFile(path.getParent,path.getFileName.toString,"tmp")
    val out = Files.newOutputStream(tmpfile, StandardOpenOption.WRITE)
    val i = Iteratee.fold[Array[Byte],OutputStream](out){ (out, bytes ) =>
      try {
        out.write(bytes)
      } catch {
        case outerr: IOException => Error("Problem writing bytes: "+outerr, El(bytes))
      }
      out
    }
    i.mapDone{ _ =>
       Files.move(tmpfile,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
       this // we can return this
    }
  }

  //this will probably require an agent to push things along.
  def reader(chunkSize: Int=1024*8) = Enumerator.fromFile(path.toFile,chunkSize)

}

/**
 * it's important for the uris in the graph to be absolute
 * this invariant is assumed by the sparql engine (TripleSource)
 */
case class LocalLDPR[Rdf<:RDF](location: Rdf#URI,
                                  graph: Rdf#Graph,
                                  updated: Option[Date] = Some(new Date))
                                 (implicit val ops: RDFOps[Rdf])
  extends LDPR[Rdf] with LocalNamedResource[Rdf]{
  import ops._
  def meta = PointedGraph(location,Graph.empty)  //todo: build up aclPath from local info
}


case class RemoteLDPR[Rdf<:RDF](location: Rdf#URI, graph: Rdf#Graph, meta: PointedGraph[Rdf], updated: Option[Date])
                               (implicit val ops: RDFOps[Rdf]) extends LDPR[Rdf] {
  import diesel._

  val link = IANALinkPrefix[Rdf]

  /**
   * location of initial ACL for this resource
   **/
  lazy val acl: Option[Rdf#URI] = (meta/link.acl).collectFirst{ case PointedGraph(p: Rdf#URI,g) => p }
}

case class Scrpt[Rdf<:RDF,A](script:LDPCommand.Script[Rdf,A])
case class Cmd[Rdf<:RDF,A](command: LDPCommand[Rdf, LDPCommand.Script[Rdf,A]])


object RWWeb {

  val logger = LoggerFactory.getLogger(this.getClass)

  def apply[Rdf<:RDF](baseUri: Rdf#URI, root: Path, cache: Option[Props])
                     (implicit ops: RDFOps[Rdf], timeout: Timeout = Timeout(5000)): RWWeb[Rdf] =
    new RWWeb(baseUri)


}

case class ParentDoesNotExist(message: String) extends Exception(message) with BananaException
case class ResourceDoesNotExist(message: String) extends Exception(message) with BananaException
case class RequestNotAcceptable(message: String) extends Exception(message) with BananaException
case class AccessDenied(message: String) extends Exception(message) with BananaException
case class PreconditionFailed(message: String) extends Exception(message) with BananaException
case class UnsupportedMediaType(message: String) extends Exception(message) with BananaException

trait RWW[Rdf <: RDF] {  //not sure which of exec or execute is going to be needed
  def system: ActorSystem
  def execute[A](script: LDPCommand.Script[Rdf,A]): Future[A]
  def exec[A](cmd: LDPCommand[Rdf, LDPCommand.Script[Rdf,A]]): Future[A]
  /*
   * these two functions look wrong and very Java-y.
   * I don't know what the right patter to do this is. Setting it up in a constructor seems too
   * restrictive
   */
  def setWebActor(webActor: ActorRef)
  def setLDPSActor(ldpsActor: ActorRef)
  def shutdown(): Unit
}

class RWWeb[Rdf<:RDF](val baseUri: Rdf#URI)
                           (implicit ops: RDFOps[Rdf], timeout: Timeout) extends RWW[Rdf] {
  val system = ActorSystem("plantain")
  val rwwActorRef = system.actorOf(Props(new RWWebActor(baseUri)),name="rww")
  import RWWeb.logger

  logger.info(s"Created rwwActorRef=<$rwwActorRef>")

  val listener = system.actorOf(Props(new Actor {
    def receive = {
      case d: DeadLetter if ( d.message.isInstanceOf[Scrpt[_,_]] || d.message.isInstanceOf[Cmd[_,_]] ) ⇒ {
        d.sender !  akka.actor.Status.Failure(ResourceDoesNotExist(s"could not find actor for ${d.recipient}"))
      }
    }
  }))
  system.eventStream.subscribe(listener, classOf[DeadLetter])


  def execute[A](script: LDPCommand.Script[Rdf, A]) = {
    (rwwActorRef ? Scrpt[Rdf,A](script)).asInstanceOf[Future[A]]
  }

  def exec[A](cmd: LDPCommand[Rdf, LDPCommand.Script[Rdf,A]]) = {
    (rwwActorRef ? Cmd(cmd)).asInstanceOf[Future[A]]
  }

  def shutdown(): Unit = {
    system.shutdown()
  }

  def setWebActor(ref: ActorRef) {
    rwwActorRef ! WebActor(ref)
  }

  def setLDPSActor(ldpsActor: ActorRef) {
    rwwActorRef ! LDPSActor(ldpsActor)
  }
}


case class WebActor(web: ActorRef)
case class LDPSActor(ldps: ActorRef)

class RWWebActor[Rdf<:RDF](val baseUri: Rdf#URI)
                             (implicit ops: RDFOps[Rdf], timeout: Timeout) extends RActor {
  import syntax.URISyntax.uriW

  var rootContainer: Option[ActorRef] = None
  var web : Option[ActorRef] = None


  def receive = returnErrors {
    case Scrpt(script) => {
       script.resume match {
         case command: -\/[LDPCommand[Rdf, LDPCommand.Script[Rdf,_]]] => forwardSwitch(Cmd(command.a))
         case \/-(res) => sender ! res
       }
    }
    case cmd: Cmd[Rdf,_] => forwardSwitch(cmd)
    case WebActor(webActor) => {
      log.info(s"setting web actor to <$webActor> ")
      web = Some(webActor)
    }
    case LDPSActor(ldps) => {
       log.info(s"setting rootContainer to <$ldps> ")
       rootContainer = Some(ldps)
    }
  }

  /** We in fact ignore the R and A types, since we cannot capture */
  protected def forwardSwitch[A](cmd: Cmd[Rdf,A]) {
      local(cmd.command.uri.underlying,baseUri.underlying).map { path=>
        rootContainer match {
          case Some(root) => {
            val p = root.path / path.split('/').toIterable
            val to = context.actorFor(p)
            if (context.system.deadLetters == to) {
              log.info(s"message $cmd to akka('$path')=$to -- dead letter - returning error ")
              sender ! ResourceDoesNotExist(s"could not find actor for ${cmd.command.uri}")
            }
            else {
              log.info(s"forwarding message $cmd to akka('$path')=$to ")
              to forward cmd
            }
          }
          case None => log.warning("RWWebActor not set up yet: missing rootContainer")
        }
    } getOrElse {
      //todo: this relative uri comparison is too simple.
      //     really one should look to see if it
      //     is the same host and then send it to the local lpdserver ( because a remote server may
      //     link to this server ) and if so there is no need to go though the external http layer to
      //     fetch graphs
      web.map {
        log.info(s"sending message $cmd to general web agent <$web>")
        _ forward cmd
      }.getOrElse(log.warning("RWWebActor not set up yet: missing web actor"))
    }

  }


}