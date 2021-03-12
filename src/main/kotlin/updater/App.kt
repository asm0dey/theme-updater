@file:Suppress("DEPRECATION")

package updater

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN
import com.github.kotlintelegrambot.network.fold
import com.github.kotlintelegrambot.webhook
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.apache.commons.lang3.text.translate.LookupTranslator
import org.dizitart.kno2.documentOf
import org.dizitart.kno2.filters.and
import org.dizitart.kno2.filters.eq
import org.dizitart.kno2.filters.within
import org.dizitart.kno2.getRepository
import org.dizitart.kno2.nitrite
import org.dizitart.no2.objects.Id
import org.dizitart.no2.objects.Index
import org.dizitart.no2.objects.Indices
import org.dizitart.no2.objects.filters.ObjectFilters.ALL
import org.dizitart.no2.tool.Exporter
import java.io.File
import java.nio.file.Paths
import java.time.LocalDate
import java.util.*

data class ThemeInfo(@Id val channel: Long, val messageId: Long, val tasks: List<String> = listOf())
data class ShortList(@Id val channel: Long, val topics: Set<ShortlistTopic> = setOf())

data class ShortlistTopic(val id: Int, val text: String)

@Indices(Index("channel", type = NonUnique), Index("id", type = NonUnique))
data class TopicInfoV2(
    val id: Long,
    val channel: Long,
    val text: String,
    val author: String,
    val date: LocalDate,
) {
    override fun toString() = "${id}. \uD83D\uDCCC _${date}_: $text | by $author"
}

@Indices(Index("channel", type = NonUnique), Index("topicId", type = NonUnique))
data class ShortlistTopicV2(
    val channel: Long,
    val topicId: Long,
)

@Indices(Index("channelId", type = Unique))
data class ChannelInfo(
    val channelId: Long,
    val nextItemId: Long = 2L,
    val pinnedMessageId: Long? = null,
)

val db = nitrite {
    file = Paths.get(System.getProperty("user.home"), ".theme_updater.rc").toFile()
    autoCommitBufferSize = 2048
    compress = false
    autoCompact = true
}

val TOKEN = "notoken"


fun main() {
    migrate1()

    val bot = bot {
        token = TOKEN
        webhook {
            url = "https://theme-updater.bot.asm0dey.ru/$TOKEN"
            maxConnections = 50
            allowedUpdates = listOf()
        }
        dispatch {
            command("addtopic") {
                val chatId = message.chat.id
                val text = message
                    .updatesText()
                    ?: let {
                        bot.sendMessage(chatId, "Sorry, empty topic")
                        return@command
                    }
                val mention = with(message.from) {
                    val user = this?.let { it.username ?: listOfNotNull(it.lastName, it.firstName).joinToString(" ") }
                    "[$user](tg://user?id=${this?.id})"
                }
                bot.addTopicAndUpdatePinned(chatId, text, mention, message.messageId)
            }
            command("remove") {
                val chatId = message.chat.id
                val topicId = args.takeIf { it.isNotEmpty() }?.first()?.toLongOrNull()
                    ?: let {
                        bot.throwTable(chatId, "NoSuchElementException")
                        return@command
                    }
                if (!inLongList(chatId, topicId)) {
                    bot.throwTable(chatId, "There is no topic #$topicId on list!")
                }
                if (inShortList(chatId, topicId)) {
                    bot.sendMessage(
                        chatId,
                        "Sorry, this topic is still in short list, please remove it from short list first."
                    )
                    return@command
                }
                db.getRepository<TopicInfoV2>()
                    .remove((TopicInfoV2::channel eq chatId) and (TopicInfoV2::id eq topicId))
                bot.updatePinnedMessage(chatId, message.messageId)
            }
            command("help") {
                val chatId = message.chat.id
                bot.sendMessage(
                    chatId,
                    """**Supported commands**:
                    |
                    |`/addtopic` <text> — добавить тему для обсуждения
                    |`/remove` <number> — удалить тему по номеру из списка на обсуждение
                    |`/list` — Актуальные темы
                    |`/help` — это сообщение
                    |`/shortlist`:
                    |  - `add` <number> - Add topic to shortlist by id
                    |  - `remove` <number> - Remove topic from shortlist by id
                    |  - `print` - Print current shortlist (default)
                    |  - `done` - Purge shortlist adnd remove all it's items from long list""".trimMargin(),
                    parseMode = MARKDOWN
                )
            }
            command("list") {
                val chatId = message.chat.id
                bot.sendMessage(chatId, messageFromTasks(topicsByChat(chatId)), MARKDOWN).fold {
                    bot.failedReply(chatId, message.messageId)
                }
            }
            command("recreate") {
                bot.recreateList(message.chat.id)
            }
            command("shortlist") {
                val chatId = message.chat.id
                val subCommands = ArrayDeque(args)
                if (subCommands.isEmpty()) subCommands.add("print")
                when (subCommands.removeFirst()) {
                    "add" -> addToShorlist(bot, chatId, subCommands, message.messageId)
                    "remove" -> removeFromShortlist(bot, chatId, subCommands, message.messageId)
                    "print" -> bot.printShortList(chatId, message.messageId)
                    "done" -> clearShortList(chatId, bot, message.messageId)
                }
            }
            command("export") {
                val tmp = File.createTempFile("export", ".dat").also { it.deleteOnExit() }
                Exporter.of(db)
                    .exportTo(tmp)
                bot.sendDocument(
                    message.chat.id,
                    tmp,
                    "#dump from ${LocalDate.now()}",
                    replyToMessageId = message.messageId
                )
                tmp.delete()
            }
/*
            command("guests") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                val subCommands = ArrayDeque(list)
                if (subCommands.isEmpty()) subCommands.add("print")
                when (subCommands.removeFirst()) {
                    "add" -> addToGuestList(bot, update, chatId, subCommands)
                    "remove" -> removeFromShortlist(bot, update, chatId, subCommands)
                    "print" -> db.getRepository<ShortList> {
                        val shortList = find(ShortList::channel eq chatId).firstOrNull() ?: run {
                            bot.throwTable(chatId, "No shortlist yet!")
                            return@getRepository
                        }
                        val text = shortList.topics.joinToString("\n") { "${it.id}: ${it.text}" }
                        bot.sendMessage(chatId, text, parseMode = MARKDOWN)
                    }
                    "done" -> {
                        if (subCommands.size > 0) {
                            bot.throwTable(chatId, "done is not accepting any params")
                            return@command
                        }
                        db.getRepository<ShortList> {
                            val shortList = find(ShortList::channel eq chatId).firstOrNull() ?: run {
                                bot.throwTable(chatId, "No shortlist yet!")
                                return@getRepository
                            }
                            val toRemove = shortList.topics.asSequence().map { it.id }.sortedDescending().toList()
                            db.getRepository<ThemeInfo> {
                                val themeInfo = find(ThemeInfo::channel eq chatId).first()
                                val filtered = themeInfo.tasks.filterIndexed { idx, _ -> !toRemove.contains(idx + 1) }
                                updatePinnedMessage(filtered, bot, themeInfo, chatId, themeInfo.messageId)
                            }
                            update(shortList.copy(topics = setOf()))

                        }

                    }
                }

            }
*/
        }
    }
    bot.startWebhook()
//    bot.deleteWebhook()
//    bot.startPolling()
    val env = applicationEngineEnvironment {
        module {
            install(CallLogging)
            routing {
                post("/$TOKEN") {
                    val response = call.receiveText()
                    bot.processUpdate(response)
                    call.respond(OK)
                }
            }
        }
        connector {
            host = "127.0.0.1"
            port = 7443
        }
    }
    embeddedServer(Netty, env).start(wait = true)
}

private fun clearShortList(chatId: Long, bot: Bot, sourceMessageId: Long) {
    db.getRepository<ShortlistTopicV2> {
        val topics = find(ShortlistTopicV2::channel eq chatId).map { it.topicId }
        remove(ShortlistTopicV2::channel eq chatId)
        db.getRepository<TopicInfoV2>().remove(TopicInfoV2::channel eq chatId and (TopicInfoV2::id within topics))
        bot.updatePinnedMessage(chatId, sourceMessageId)
    }
}

private fun Bot.printShortList(chatId: Long, sourceMessageId: Long) {
    val shortlistedTopics = db
        .getRepository<ShortlistTopicV2>()
        .find(ShortlistTopicV2::channel eq chatId)
        .map { it.topicId }

    if (shortlistedTopics.none()) {
        sendMessage(chatId, "There are no items on short list", replyToMessageId = sourceMessageId)
        return
    }
    val found = db.getRepository<TopicInfoV2>()
        .find(
            TopicInfoV2::channel eq chatId and (TopicInfoV2::id within shortlistedTopics),
            sort("id", Ascending)
        )
    sendMessage(chatId, messageFromTasks(found), replyToMessageId = sourceMessageId, parseMode = MARKDOWN)

}

private fun Bot.addTopicAndUpdatePinned(
    chatId: Long,
    text: String,
    userMention: String,
    sourceMessageId: Long
) {
    db.getRepository<TopicInfoV2> {
        val task = TopicInfoV2(
            findNextTopicId(chatId),
            chatId,
            text,
            userMention,
            LocalDate.now()
        )
        insert(task)
        db.getRepository<ChannelInfo> {
            val found = find(ChannelInfo::channelId eq chatId).single()
            if (found.pinnedMessageId == null)
                createPinnedMessage(chatId, task, found, sourceMessageId)
            else
                updatePinnedMessage(chatId, sourceMessageId)
        }
    }
}

private fun Bot.createPinnedMessage(
    chatId: Long,
    task: TopicInfoV2,
    found: ChannelInfo,
    sourceMessage: Long
) {
    sendMessage(chatId, task.toString(), parseMode = MARKDOWN)
        .fold({ ok ->
            val messageId = ok?.result?.messageId ?: return@fold
            pinChatMessage(chatId, messageId, disableNotification = true)
            db.getRepository<ChannelInfo>().update(found.copy(pinnedMessageId = messageId))
        }, {
            failedReply(chatId, sourceMessage)
        })
}

fun inLongList(chatId: Long, topicId: Long): Boolean = !db
    .getRepository<TopicInfoV2>()
    .find((TopicInfoV2::channel eq chatId) and (TopicInfoV2::id eq topicId))
    .none()

fun inShortList(channelId: Long, topicId: Long): Boolean = !db
    .getRepository<ShortlistTopicV2>()
    .find((ShortlistTopicV2::channel eq channelId) and (ShortlistTopicV2::topicId eq topicId))
    .none()

private fun Bot.recreateList(chatId: Long) {
    db.getRepository<TopicInfoV2> {
        val foundInfo = topicsByChat(chatId)
        if (foundInfo.none()) throwTable(chatId, "Unsupported chat!")
        else sendMessage(chatId, messageFromTasks(foundInfo), parseMode = MARKDOWN).fold({
            val messageId = it?.result?.messageId ?: return@fold
            unpinChatMessage(chatId)
            pinChatMessage(chatId, messageId, disableNotification = true)
            db.getRepository<ChannelInfo>().update(
                ChannelInfo::channelId eq chatId,
                documentOf("pinnedMessageId" to messageId)
            )
        })
    }
}

fun messageFromTasks(tasks: Iterable<TopicInfoV2>): String = tasks.joinToString("\n")

fun migrate1() {
    val cursor = db.getRepository<ThemeInfo>().find()
    if (cursor.none()) return
    db.getRepository<ThemeInfo> {
        val infos = cursor.flatMap { themeInfo ->
            themeInfo.tasks.withIndex().map { (index, text) ->
                val body = text.substringAfter(':').substringBefore('|').trim()
                val date =
                    LocalDate.parse(text.substringBefore(':').replace("_", "").substringAfter("\uD83D\uDCCC").trim())
                val author = text.substringAfter('|').substringAfter("by").trim()
                TopicInfoV2(index.toLong(), themeInfo.channel, body, author, date)
            }
        }.toTypedArray()
        db.getRepository<TopicInfoV2>().insert(infos)
        find().forEach {
            db.getRepository<ChannelInfo>().insert(ChannelInfo(it.channel, (it.tasks.size + 1).toLong(), it.messageId))
        }
        remove(ALL)

    }
    db.getRepository<ShortList> {

        val newTopics = find().flatMap { item ->
            item.topics.map { z ->
                ShortlistTopicV2(item.channel, z.id.toLong())
            }
        }
        if (!newTopics.none())
            db.getRepository<ShortlistTopicV2>().insert(newTopics.toTypedArray())
        remove(ALL)
    }
}

private fun removeFromShortlist(
    bot: Bot, chatId: Long, subCommands: ArrayDeque<String>, sourceMessageId: Long
) {
    if (subCommands.size != 1 || subCommands.first.toLongOrNull() == null) {
        bot.throwTable(chatId, "Provide me wid id of topic (ONE)!")
        return
    }
    val topicId = subCommands.first.toLong()
    db.getRepository<ShortlistTopicV2>()
        .remove((ShortlistTopicV2::channel eq chatId) and (ShortlistTopicV2::topicId eq topicId))
    bot.okReply(chatId, sourceMessageId)
}

private fun addToShorlist(bot: Bot, chatId: Long, subCommands: ArrayDeque<String>, sourceMessageId: Long) {
    if (subCommands.size != 1 || subCommands.first.toLongOrNull() == null) {
        bot.throwTable(chatId, "Provide me wid id of topic (ONE)!")
        return
    }
    val topicId = subCommands.first.toLong()
    when {
        inShortList(chatId, topicId) -> bot.sendMessage(
            chatId,
            "It's already in the short list",
            replyToMessageId = sourceMessageId
        )
        !inLongList(chatId, topicId) -> bot.sendMessage(
            chatId,
            "There is no such topic!",
            replyToMessageId = sourceMessageId
        )
        else -> {
            db.getRepository<ShortlistTopicV2>().insert(ShortlistTopicV2(chatId, topicId))
            bot.okReply(chatId, sourceMessageId)
        }
    }
}

private fun Bot.throwTable(chatId: Long, message: String) {
    sendMessage(chatId, "$message `(╯°□°)╯︵ ┻━┻`", parseMode = MARKDOWN)
}

private fun Message.updatesText(): String? =
    (replyToMessage?.text ?: text)
        ?.replace(Regex("/addtopic(@[a-zA-Z0-9_]+)?"), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.escape()

private fun Bot.updatePinnedMessage(
    chatId: Long,
    sourceMessageId: Long,
) {
    val newTasksText = messageFromTasks(topicsByChat(chatId))
    val (channelId, _, messageId) = channelInfoByChat(chatId).single()
    editMessageText(
        channelId,
        messageId,
        text = if (newTasksText.isBlank()) "Нету тем `¯\\_(ツ)_/¯`" else newTasksText,
        parseMode = MARKDOWN
    )
        .fold({
            unpinChatMessage(chatId)
            pinChatMessage(chatId, it?.result?.messageId!!, disableNotification = true)
            okReply(chatId, sourceMessageId)
        }, {
            it.exception?.printStackTrace()
            recreateList(chatId)
            failedReply(chatId, sourceMessageId)
        })
}

private fun channelInfoByChat(chatId: Long) =
    db.getRepository<ChannelInfo>().find(ChannelInfo::channelId eq chatId)

private fun topicsByChat(chatId: Long) =
    db.getRepository<TopicInfoV2>().find(TopicInfoV2::channel eq chatId, FindOptions.sort("id", Ascending))

private fun Bot.okReply(chatId: Long, messageId: Long?) {
    sendMessage(chatId, replyToMessageId = messageId, text = "✔️")
}

private fun Bot.failedReply(chatId: Long, messageId: Long?) {
    sendMessage(chatId, replyToMessageId = messageId, text = "❌")
}

val lookupTranslator = LookupTranslator(arrayOf("_", "\\_"))

private fun String?.escape(): String? = lookupTranslator.translate(this)

fun findNextTopicId(channelId: Long): Long {
    var result = 0L
    db.getRepository<ChannelInfo> {
        val found = find(ChannelInfo::channelId eq channelId).firstOrNull()
        result = if (found == null) {
            insert(ChannelInfo(channelId))
            1L
        } else {
            update(ChannelInfo::channelId eq channelId, found.copy(nextItemId = found.nextItemId + 1))
            found.nextItemId
        }
    }
    return result
}
