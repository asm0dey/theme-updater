@file:Suppress("DEPRECATION")

package updater

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN
import com.github.kotlintelegrambot.entities.Update
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
import org.dizitart.kno2.filters.eq
import org.dizitart.kno2.getRepository
import org.dizitart.kno2.nitrite
import org.dizitart.no2.objects.Id
import org.dizitart.no2.objects.ObjectRepository
import org.dizitart.no2.tool.Exporter
import java.io.File
import java.nio.file.Paths
import java.time.LocalDate
import java.util.ArrayDeque

data class ThemeInfo(@Id val channel: Long, val messageId: Long, val tasks: List<String> = listOf())
data class ShortList(@Id val channel: Long, val topics: Set<ShortlistTopic> = setOf())

data class ShortlistTopic(val id: Int, val text: String)

val db = nitrite {
    file = Paths.get(System.getProperty("user.home"), ".theme_updater.rc").toFile()
    autoCommitBufferSize = 2048
    compress = false
    autoCompact = true
}

val TOKEN = "notoken"


fun main() {

    val bot = bot {
        token = TOKEN
        webhook {
            url = "https://theme-updater.bot.asm0dey.ru/$TOKEN"
            maxConnections = 50
            allowedUpdates = listOf()
        }
        dispatch {
            command("addtopic") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                val text = update
                        .updatesText()
                        ?: let {
                            bot.sendMessage(chatId, "Sorry, empty topic")
                            return@command
                        }
                val mention = with(update.message?.from) {
                    val user = this?.let { it.username ?: listOfNotNull(it.lastName, it.firstName).joinToString(" ") }
                    "[$user](tg://user?id=${this?.id})"
                }
                db.getRepository<ThemeInfo> {
                    val foundInfo = find(ThemeInfo::channel eq chatId).firstOrNull()
                    val task = "\uD83D\uDCCC _${LocalDate.now()}_: $text | by $mention"
                    if (foundInfo == null) {
                        bot
                                .sendMessage(chatId, "1. $task", parseMode = MARKDOWN)
                                .fold({ ok ->
                                    val messageId = ok?.result?.messageId ?: return@fold
                                    bot.pinChatMessage(chatId, messageId, disableNotification = true)
                                    insert(ThemeInfo(chatId, messageId, listOf(task)))
                                    bot.okReply(chatId, update.message?.messageId)
                                }, {
                                    bot.failedReply(chatId, update.message?.messageId)
                                })
                    } else {
                        val newTasks = foundInfo.tasks + task
                        updatePinnedMessage(newTasks, bot, foundInfo, chatId, update.message?.messageId)
                    }
                }
            }
            command("remove") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                val text = update.message?.text?.replace(Regex("/remove(@[a-zA-Z0-9_]+)?\\s+"), "")?.toIntOrNull()
                        ?: let {
                            bot.throwTable(chatId, "NoSuchElementException")
                            return@command
                        }
                db.getRepository<ThemeInfo> {
                    val foundInfo = find(ThemeInfo::channel eq chatId).firstOrNull() ?: kotlin.run {
                        bot.sendMessage(chatId, "Unsupported chat! `(╯°□°)╯︵ ┻━┻`", parseMode = MARKDOWN)
                        return@getRepository
                    }
                    val newTasks = foundInfo.tasks.filterIndexed { index, _ -> index + 1 != text }
                    updatePinnedMessage(newTasks, bot, foundInfo, chatId, update.message?.messageId)
                }
            }
            command("help") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                bot.sendMessage(chatId, """**Supported commands**:
                    |
                    |`/addtopic` <text> — добавить тему для обсуждения
                    |`/remove` <number> — удалить тему по номеру из списка на обсуждение
                    |`/list` — Актуальные темы
                    |`/help` — это сообщение
                    |`/shortlist`:
                    |  - `add` <number> - Add topic to shortlist by id
                    |  - `remove` <number> - Remove topic from shortlist by id
                    |  - `print` - Print current shortlist
                    |  - `done` - Purge shortlist adnd remove all it's items from long list""".trimMargin(), parseMode = MARKDOWN)
            }
            command("list") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                db.getRepository<ThemeInfo> {
                    val foundInfo = find(ThemeInfo::channel eq chatId).firstOrNull() ?: kotlin.run {
                        bot.throwTable(chatId, "Unsupported chat!")
                        return@getRepository
                    }
                    val msg = messageFromTasks(foundInfo.tasks)
                    bot.sendMessage(chatId, msg, parseMode = MARKDOWN)
                }
            }
            command("recreate") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                db.getRepository<ThemeInfo> {
                    val foundInfo = find(ThemeInfo::channel eq chatId).firstOrNull()
                    if (foundInfo == null) bot.throwTable(chatId, "Unsupported chat!")
                    else bot.sendMessage(chatId, messageFromTasks(foundInfo.tasks), parseMode = MARKDOWN).fold({
                        update(foundInfo.copy(messageId = it?.result?.messageId ?: return@fold))
                    })
                }

            }
            command("shortlist") { bot, update, list ->
                val chatId = update.message?.chat?.id ?: return@command
                if (list.isEmpty()) bot.throwTable(chatId, "Subcommand is not passed!")
                val subCommands = ArrayDeque(list)
                if (subCommands.isEmpty()) bot.throwTable(chatId, "Subcommand is not passed!")
                when (subCommands.removeFirst()) {
                    "add" -> addToShorlist(bot, update, chatId, subCommands)
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
            command("export") { bot, update ->
                val tmp = File.createTempFile("export", ".dat").also { it.deleteOnExit() }
                Exporter.of(db)
                        .exportTo(tmp)
                bot.sendDocument(
                        update.message?.chat?.id ?: return@command,
                        tmp,
                        "#dump from ${LocalDate.now()}",
                        replyToMessageId = update.message?.messageId
                )
                tmp.delete()
            }
        }
    }
    bot.startWebhook()

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

private fun removeFromShortlist(bot: Bot, update: Update, chatId: Long, subCommands: ArrayDeque<String>) {
    if (subCommands.size != 1 || subCommands.first.toIntOrNull() == null) {
        bot.throwTable(chatId, "Provide me wid id of topic (ONE)!")
        return
    }
    val topicId = subCommands.first.toInt()
    db.getRepository<ShortList> {
        val shortList = find(ShortList::channel eq chatId).firstOrNull() ?: run {
            bot.throwTable(chatId, "Unsupported chat!")
            return@getRepository
        }
        val newTopics = shortList.topics.filterNot { it.id == topicId }.toSet()
        update(shortList.copy(topics = newTopics))
        bot.okReply(chatId, update.message?.messageId)
    }
}

private fun addToShorlist(bot: Bot, update: Update, chatId: Long, subCommands: ArrayDeque<String>) {
    if (subCommands.size != 1 || subCommands.first.toIntOrNull() == null) {
        bot.throwTable(chatId, "Provide me wid id of topic (ONE)!")
        return
    }
    val topicId = subCommands.first.toInt()
    db.getRepository<ThemeInfo> {
        val foundInfo = find(ThemeInfo::channel eq chatId).firstOrNull()
        if (foundInfo == null) bot.throwTable(chatId, "Unsupported chat!")
        else {
            if (topicId > foundInfo.tasks.size) {
                bot.throwTable(chatId, "Incorrect topic index! Longlist have only the ${foundInfo.tasks.size} topics.")
                return@getRepository
            }
            val topic = foundInfo.tasks[topicId - 1]
            db.getRepository<ShortList> {
                val shortList = find(ShortList::channel eq chatId).firstOrNull() ?: run {
                    insert(ShortList(chatId))
                    find(ShortList::channel eq chatId).first()
                }
                val newTopics = shortList.topics + ShortlistTopic(topicId, topic)
                update(shortList.copy(topics = newTopics))
                bot.okReply(chatId, update.message?.messageId)
            }
        }
    }
}

private fun Bot.throwTable(chatId: Long, message: String) {
    sendMessage(chatId, "$message `(╯°□°)╯︵ ┻━┻`", parseMode = MARKDOWN)
}

private fun Update.updatesText(): String? = message
        ?.messageText()
        ?.replace(Regex("/addtopic(@[a-zA-Z0-9_]+)?"), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.escape()

private fun Message.messageText(): String? = replyToMessage?.text ?: text

private fun ObjectRepository<ThemeInfo>.updatePinnedMessage(newTasks: List<String>, bot: Bot, foundInfo: ThemeInfo, chatId: Long, messageId: Long?) {
    val newTasksText = messageFromTasks(newTasks)
    bot
            .editMessageText(foundInfo.channel, foundInfo.messageId, text = if (newTasksText.isBlank()) "Нету тем `¯\\_(ツ)_/¯`" else newTasksText, parseMode = MARKDOWN)
            .fold({
                update(foundInfo.copy(tasks = newTasks))
                bot.pinChatMessage(chatId, foundInfo.messageId, disableNotification = true)
                bot.okReply(chatId, messageId)
            }, {
                it.exception?.printStackTrace()
                bot.failedReply(chatId, messageId)
            })
}

private fun Bot.okReply(chatId: Long, messageId: Long?) {
    sendMessage(chatId, replyToMessageId = messageId, text = "✔️")
}

private fun Bot.failedReply(chatId: Long, messageId: Long?) {
    sendMessage(chatId, replyToMessageId = messageId, text = "❌")
}

private fun messageFromTasks(tasks: List<String>): String {
    return tasks.mapIndexed { index, s -> "${index + 1}. $s" }.joinToString("\n", postfix = "\n")
}

val lookupTranslator = LookupTranslator(
        arrayOf("_", "\\_")
)


private fun String?.escape(): String? = lookupTranslator.translate(this)
