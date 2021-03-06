package lila.message

import org.joda.time.DateTime

import lila.shutup.Analyser
import lila.user.User
import lila.hub.actorApi.report.AutoFlag

final private[message] class MessageSecurity(
    relationApi: lila.relation.RelationApi,
    prefApi: lila.pref.PrefApi,
    spam: lila.security.Spam
)(implicit ec: scala.concurrent.ExecutionContext) {

  import lila.pref.Pref.Message._

  def canMessage(from: User.ID, to: User.ID): Fu[Boolean] =
    relationApi.fetchBlocks(to, from) flatMap {
      case true => fuFalse
      case false =>
        prefApi.getPref(to).dmap(_.message) flatMap {
          case NEVER  => fuFalse
          case FRIEND => relationApi.fetchFollows(to, from)
          case ALWAYS => fuTrue
        }
    }

  def muteThreadIfNecessary(thread: Thread, creator: User, invited: User): Fu[Thread] = {
    val fullText = s"${thread.name} ${~thread.firstPost.map(_.text)}"
    if (spam.detect(fullText)) {
      logger.warn(s"PM spam from ${creator.username}: $fullText")
      fuTrue
    } else if (creator.marks.troll) !relationApi.fetchFollows(invited.id, creator.id)
    else if (Analyser(fullText).dirty && creator.createdAt.isAfter(DateTime.now.minusDays(30))) {
      relationApi.fetchFollows(invited.id, creator.id) map { f =>
        if (!f) thread.firstPost.foreach { post =>
          lila.common.Bus.publish(
            AutoFlag(creator.id, s"message/${thread.id}", thread flaggableText post),
            "autoFlag"
          )
        }
        !f
      }
    } else fuFalse
  } map { mute =>
    if (mute) thread deleteFor invited
    else thread
  }
}
