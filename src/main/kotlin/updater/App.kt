package updater

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN
import com.github.kotlintelegrambot.network.fold
import org.apache.commons.lang3.text.translate.LookupTranslator
import org.dizitart.kno2.filters.eq
import org.dizitart.kno2.getRepository
import org.dizitart.kno2.nitrite
import org.dizitart.no2.objects.Id
import org.dizitart.no2.objects.ObjectRepository
import java.nio.file.Paths
import java.time.LocalDate

data class ThemeInfo(@Id val channel: Long, val messageId: Long, val tasks: List<String> = listOf())

val db = nitrite {
    file = Paths.get(System.getProperty("user.home"), ".theme_updater.rc").toFile()
    autoCommitBufferSize = 2048
    compress = false
    autoCompact = true
}

fun main() {

    val bot = bot {
        token = "api"
        dispatch {
            command("addtopic") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                val text = -(update.message?.text?.replace(Regex("/addtopic(@[a-zA-Z0-9_]+)? "), "") ?: let {
                    bot.sendMessage(chatId, "Sorry, empty topic")
                    return@command
                })
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
                            bot.sendMessage(chatId, "NoSuchElementException `(╯°□°)╯︵ ┻━┻`", parseMode = MARKDOWN)
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
                    |`/help` — это сообщение""".trimMargin(), parseMode = MARKDOWN)
            }
            command("list") { bot, update ->
                val chatId = update.message?.chat?.id ?: return@command
                db.getRepository<ThemeInfo> {
                    val foundInfo = find(ThemeInfo::channel eq chatId).firstOrNull() ?: kotlin.run {
                        bot.sendMessage(chatId, "Unsupported chat! `(╯°□°)╯︵ ┻━┻`", parseMode = MARKDOWN)
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
                    if (foundInfo == null) {
                        bot.sendMessage(chatId, "Unsupported chat! `(╯°□°)╯︵ ┻━┻`", parseMode = MARKDOWN)
                    } else {
                        bot.sendMessage(chatId, messageFromTasks(foundInfo.tasks), parseMode = MARKDOWN).fold({
                            update(foundInfo.copy(messageId = it?.result?.messageId ?: return@fold))
                        })
                    }
                }

            }
        }
    }
    bot.startPolling()
}

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

private operator fun String.unaryMinus(): String = lookupTranslator.translate(this)