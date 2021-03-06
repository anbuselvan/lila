package lila.tournament

import akka.stream.Materializer
import akka.stream.scaladsl._
import org.joda.time.DateTime
import reactivemongo.akkastream.{ cursorProducer, AkkaStreamCursor }
import reactivemongo.api.bson._
import reactivemongo.api.ReadPreference

import BSONHandlers._
import lila.db.dsl._
import lila.game.Game
import lila.user.User

final class PairingRepo(coll: Coll)(implicit ec: scala.concurrent.ExecutionContext, mat: Materializer) {

  def selectTour(tourId: Tournament.ID) = $doc("tid" -> tourId)
  def selectUser(userId: User.ID)       = $doc("u"   -> userId)
  private def selectTourUser(tourId: Tournament.ID, userId: User.ID) = $doc(
    "tid" -> tourId,
    "u"   -> userId
  )
  private val selectPlaying  = $doc("s" $lt chess.Status.Mate.id)
  private val selectFinished = $doc("s" $gte chess.Status.Mate.id)
  private val recentSort     = $doc("d" -> -1)
  private val chronoSort     = $doc("d" -> 1)

  def byId(id: Tournament.ID): Fu[Option[Pairing]] = coll.ext.find($id(id)).one[Pairing]

  private[tournament] def lastOpponents(
      tourId: Tournament.ID,
      userIds: Iterable[User.ID],
      max: Int
  ): Fu[Pairing.LastOpponents] =
    userIds.nonEmpty.?? {
      val nbUsers = userIds.size
      coll
        .find(
          selectTour(tourId) ++ $doc("u" $in userIds),
          $doc("_id" -> false, "u" -> true).some
        )
        .sort(recentSort)
        .batchSize(20)
        .cursor[Bdoc]()
        .documentSource(max)
        .mapConcat(_.getAsOpt[List[User.ID]]("u").toList)
        .scan(Map.empty[User.ID, User.ID]) {
          case (acc, List(u1, u2)) =>
            val acc1 = if (acc.contains(u1)) acc else acc.updated(u1, u2)
            if (acc.contains(u2)) acc1 else acc1.updated(u2, u1)
        }
        .takeWhile { r =>
          r.size < nbUsers || !userIds.forall(r.contains)
        }
        .toMat(Sink.lastOption)(Keep.right)
        .run
        .dmap(~_)
    } dmap Pairing.LastOpponents.apply

  def opponentsOf(tourId: Tournament.ID, userId: User.ID): Fu[Set[User.ID]] =
    coll
      .find(
        selectTourUser(tourId, userId),
        $doc("_id" -> false, "u" -> true).some
      )
      .list[Bdoc]()
      .dmap {
        _.view.flatMap { doc =>
          ~doc.getAsOpt[List[User.ID]]("u").find(userId !=)
        }.toSet
      }

  def recentIdsByTourAndUserId(tourId: Tournament.ID, userId: User.ID, nb: Int): Fu[List[Tournament.ID]] =
    coll
      .find(
        selectTourUser(tourId, userId),
        $doc("_id" -> true).some
      )
      .sort(recentSort)
      .list[Bdoc](nb)
      .dmap {
        _.flatMap(_.getAsOpt[Game.ID]("_id"))
      }

  def playingByTourAndUserId(tourId: Tournament.ID, userId: User.ID): Fu[Option[Game.ID]] =
    coll
      .find(
        selectTourUser(tourId, userId) ++ selectPlaying,
        $doc("_id" -> true).some
      )
      .sort(recentSort)
      .one[Bdoc]
      .dmap {
        _.flatMap(_.getAsOpt[Game.ID]("_id"))
      }

  def removeByTour(tourId: Tournament.ID) = coll.delete.one(selectTour(tourId)).void

  private[tournament] def forfeitByTourAndUserId(tourId: Tournament.ID, userId: User.ID) =
    coll.ext.find(selectTourUser(tourId, userId)).list[Pairing]().flatMap {
      _.filter(_ notLostBy userId).map { p =>
        coll.update.one(
          $id(p.id),
          $set(
            "w" -> p.colorOf(userId).map(_.black)
          )
        )
      }.sequenceFu
    }

  def count(tourId: Tournament.ID): Fu[Int] =
    coll.countSel(selectTour(tourId))

  private[tournament] def countByTourIdAndUserIds(tourId: Tournament.ID): Fu[Map[User.ID, Int]] = {
    coll
      .aggregateList(maxDocs = 10000) { framework =>
        import framework._
        Match(selectTour(tourId)) -> List(
          Project($doc("u" -> true, "_id" -> false)),
          UnwindField("u"),
          GroupField("u")("nb" -> SumAll),
          Sort(Descending("nb"))
        )
      }
      .map {
        _.view.flatMap { doc =>
          doc.getAsOpt[User.ID]("_id") flatMap { uid =>
            doc.int("nb") map { uid -> _ }
          }
        }.toMap
      }
  }

  def removePlaying(tourId: Tournament.ID) = coll.delete.one(selectTour(tourId) ++ selectPlaying).void

  def findPlaying(tourId: Tournament.ID, userId: User.ID): Fu[Option[Pairing]] =
    coll.ext.find(selectTourUser(tourId, userId) ++ selectPlaying).one[Pairing]

  def isPlaying(tourId: Tournament.ID, userId: User.ID): Fu[Boolean] =
    coll.exists(selectTourUser(tourId, userId) ++ selectPlaying)

  private[tournament] def finishedByPlayerChronological(
      tourId: Tournament.ID,
      userId: User.ID
  ): Fu[Pairings] =
    coll.ext
      .find(
        selectTourUser(tourId, userId) ++ selectFinished
      )
      .sort(chronoSort)
      .list[Pairing]()

  def insert(pairing: Pairing) =
    coll.insert.one {
      pairingHandler.write(pairing) ++ $doc("d" -> DateTime.now)
    }.void

  def finish(g: lila.game.Game) =
    if (g.aborted) coll.delete.one($id(g.id)).void
    else
      coll.update
        .one(
          $id(g.id),
          $set(
            "s" -> g.status.id,
            "w" -> g.winnerColor.map(_.white),
            "t" -> g.turns
          )
        )
        .void

  def setBerserk(pairing: Pairing, userId: User.ID) = {
    if (pairing.user1 == userId) "b1".some
    else if (pairing.user2 == userId) "b2".some
    else none
  } ?? { field =>
    coll.update
      .one(
        $id(pairing.id),
        $set(field -> true)
      )
      .void
  }

  def sortedGameIdsCursor(
      tournamentId: Tournament.ID,
      batchSize: Int = 0,
      readPreference: ReadPreference = ReadPreference.secondaryPreferred
  ): AkkaStreamCursor[Bdoc] =
    coll
      .find(selectTour(tournamentId), $id(true).some)
      .sort(recentSort)
      .batchSize(batchSize)
      .cursor[Bdoc](readPreference)

  private[tournament] def rawStats(tourId: Tournament.ID): Fu[List[Bdoc]] = {
    coll.aggregateList(maxDocs = 3) { framework =>
      import framework._
      Match(selectTour(tourId)) -> List(
        Project(
          $doc(
            "_id" -> false,
            "w"   -> true,
            "t"   -> true,
            "b1"  -> $doc("$cond" -> $arr("$b1", 1, 0)),
            "b2"  -> $doc("$cond" -> $arr("$b2", 1, 0))
          )
        ),
        GroupField("w")(
          "games" -> SumAll,
          "moves" -> SumField("t"),
          "b1"    -> SumField("b1"),
          "b2"    -> SumField("b2")
        )
      )
    }
  }
}
