package lila.playban

import org.joda.time.DateTime
import reactivemongo.bson._
import reactivemongo.bson.Macros
import reactivemongo.core.commands._
import scala.concurrent.duration._

import chess.Color
import lila.db.BSON._
import lila.db.Types.Coll
import lila.game.{ Pov, Game, Player, Source }

final class PlaybanApi(coll: Coll) {

  import lila.db.BSON.BSONJodaDateTimeHandler
  import reactivemongo.bson.Macros
  private implicit val OutcomeBSONHandler = new BSONHandler[BSONInteger, Outcome] {
    def read(bsonInt: BSONInteger): Outcome = Outcome(bsonInt.value) err s"No such playban outcome: ${bsonInt.value}"
    def write(x: Outcome) = BSONInteger(x.id)
  }
  private implicit val banBSONHandler = Macros.handler[TempBan]
  private implicit val UserRecordBSONHandler = Macros.handler[UserRecord]

  private case class Blame(player: Player, outcome: Outcome)

  private def blameable(game: Game) = game.source == Source.Lobby && game.hasClock

  def abort(pov: Pov): Funit = blameable(pov.game) ?? {

    val blame =
      if (pov.game olderThan 45) pov.game.playerWhoDidNotMove map { Blame(_, Outcome.NoPlay) }
      else if (pov.game olderThan 15) none
      else pov.player.some map { Blame(_, Outcome.Abort) }

    blame match {
      case None => pov.game.userIds.map(save(Outcome.Good)).sequenceFu.void
      case Some(Blame(player, outcome)) =>
        player.userId.??(save(outcome)) >>
          pov.game.opponent(player).userId.??(save(Outcome.Good))
    }
  }

  def currentBan(userId: String): Fu[Option[TempBan]] = coll.find(
    BSONDocument("_id" -> userId, "b.0" -> BSONDocument("$exists" -> true)),
    BSONDocument("_id" -> false, "b" -> BSONDocument("$slice" -> -1))
  ).one[BSONDocument].map {
      _.flatMap(_.getAs[List[TempBan]]("b")).??(_.find(_.inEffect))
    }

  def bans(userId: String): Fu[List[TempBan]] = coll.find(
    BSONDocument("_id" -> userId, "b.0" -> BSONDocument("$exists" -> true)),
    BSONDocument("_id" -> false, "b" -> true)
  ).one[BSONDocument].map {
      ~_.flatMap(_.getAs[List[TempBan]]("b"))
    }

  private def save(outcome: Outcome): String => Funit = userId => coll.db.command {
    FindAndModify(
      collection = coll.name,
      query = BSONDocument("_id" -> userId),
      modify = Update(
        update = BSONDocument("$push" -> BSONDocument(
          "o" -> BSONDocument(
            "$each" -> List(outcome),
            "$slice" -> -20)
        )),
        fetchNewObject = true),
      upsert = true
    )
  } map2 UserRecordBSONHandler.read flatMap {
    case None         => fufail(s"can't find record for user $userId")
    case Some(record) => legiferate(record)
  } logFailure "PlaybanApi"

  private def legiferate(record: UserRecord): Funit = record.newBan ?? { ban =>
    loginfo(s"[playban] ban ${record.userId} for {$ban.mins} minutes")
    coll.update(
      BSONDocument("_id" -> record.userId),
      BSONDocument(
        "$unset" -> BSONDocument("o" -> true),
        "$push" -> BSONDocument(
          "b" -> BSONDocument(
            "$each" -> List(ban),
            "$slice" -> -30)
        )
      )
    ).void
  }
}
