import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.sink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import kotlin.streams.toList
import kotlin.system.exitProcess

/**
 * Input arguments. That's right, you gotta write code, that's how lazy this program is!
 */

// Where do you want your files
val OUTPUT_FOLDER = "/path/to/folder/"

// The first day to download
val START_DATE = LocalDate.of(2020, Month.JANUARY, 1)

// The last day to download
val END_DATE = LocalDate.now()

// Login on dailyconnect.com, find your cookie, and paste it here
val COOKIE = "seacloud1=blahblahblah"

// The ID of your kid; find it by viewing source on dailyconnect.com and looking for "Id":[KID_ID]
// Alternatively, you can just monitor network calls and find the KID_ID in the POST as you click back/forth through days
// (You can also use this to get the cookie.)
val KID_ID = "1234567890123456"

/**
 * Ignore the shamefully hacky code below, please.
 */

val PDT_FORMATTER = DateTimeFormatter.ofPattern("yyMMdd")
val OKHTTP_CLIENT = OkHttpClient()
val DATA_FOLDER = Paths.get(OUTPUT_FOLDER, "data")
val IMAGE_FOLDER = Paths.get(OUTPUT_FOLDER, "image")

fun main() {
  // Create the destination folders
  prepareFolders()

  // Download all the JSON into the data folder
  downloadJson()

  // Parse all the JSON into images
  val images = parseImages()

  // Download all the images into the images folder
  downloadImages(images)

  // Not reall sure why,
  exitProcess(0)
}

fun prepareFolders() {
  Files.createDirectories(DATA_FOLDER)
  Files.createDirectories(IMAGE_FOLDER)
}

private fun downloadJson() {
  fun buildPdtList(): List<String> {
    val list = mutableListOf<String>()
    var curr = START_DATE.minusDays(1)
    do {
      curr = curr.plusDays(1)
      list.add(PDT_FORMATTER.format(curr))
    } while (curr != END_DATE)
    return list
  }

  buildPdtList().forEach { pdt ->
    val destination = Paths.get("$OUTPUT_FOLDER/data/$pdt.json")
    if (Files.exists(destination)) {
      return@forEach
    }

    println("Downloading JSON for $pdt...")

    val response = OKHTTP_CLIENT.newCall(
      Request.Builder()
        .url("https://www.dailyconnect.com/CmdListW?cmd=StatusList")
        .method("POST", "pdt=$pdt&fmt=long&Kid=$KID_ID".toRequestBody())
        .addHeader("content-type", "application/x-www-form-urlencoded")
        .addHeader("cookie", COOKIE)
        .build()
    ).execute()

    response.writeToPath(destination)
  }
}

private fun parseImages(): List<Image> {
  val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  val adapter = moshi.adapter<Root>(Root::class.java)

  return Files.list(DATA_FOLDER)
    .toList()
    .filter { it.toString().endsWith(".json") }
    .flatMap { file ->
      val root = adapter.fromJson(Files.readString(file))!!
      root.list
        .filter { it.Photo != null }
        .mapIndexed { index, it -> Image(root.pdt, index, it.Txt, it.Photo!!) }
    }
}

private fun downloadImages(images: List<Image>) {
  images.forEach { image ->
    val index = image.index.toString().padStart(2, '0')
    val filename = "${image.pdt}-$index.jpg"

    val destination = IMAGE_FOLDER.resolve(filename)
    if (Files.exists(destination)) {
      return@forEach
    }

    println("Downloading image ${image.photoId} (${image.text})...")

    val response = OKHTTP_CLIENT.newCall(
      Request.Builder()
        .url("https://www.dailyconnect.com/GetCmd?cmd=PhotoGet&id=${image.photoId}")
        .addHeader("cookie", COOKIE)
        .build()
    ).execute()

    response.writeToPath(destination)
  }
}

private fun Response.writeToPath(path: Path) {
  val sink = path.sink().buffer()
  sink.writeAll(body!!.source())
  sink.close()
}

// Models

data class Root(val pdt: Int, val list: List<Event>)

data class Event(val Txt: String, val Photo: Long? = null)

data class Image(val pdt: Int, val index: Int, val text: String, val photoId: Long)

// Workaround for Gradle application plugin
class Main