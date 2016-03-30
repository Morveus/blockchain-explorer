package models

import play.api.Logger
import scala.async.Async.async
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.Calendar
import java.text.SimpleDateFormat

object ApiLogs {

    def debug(message: String) = {
        async {
            var d = Calendar.getInstance().getTime()
            val dFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
            Logger.debug(dFormat.format(d) + " - " + message)
        }
    }

    def info(message: String) = {
        async {
            var d = Calendar.getInstance().getTime()
            val dFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
            Logger.info(dFormat.format(d) + " - " + message)
        }
    }

    def warn(message: String) = {
        async {
            var d = Calendar.getInstance().getTime()
            val dFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
            Logger.warn(dFormat.format(d) + " - " + message)
        }
    }

    def error(message: String) = {
        async {
            var d = Calendar.getInstance().getTime()
            val dFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
            Logger.error(dFormat.format(d) + " - " + message)
        }
    }
}