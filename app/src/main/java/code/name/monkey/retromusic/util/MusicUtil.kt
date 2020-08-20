package code.name.monkey.retromusic.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.text.TextUtils
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.SongEntity
import code.name.monkey.retromusic.helper.MusicPlayerRemote.removeFromQueue
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Playlist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.lyrics.AbsSynchronizedLyrics
import code.name.monkey.retromusic.repository.RealPlaylistRepository
import code.name.monkey.retromusic.repository.SongRepository
import code.name.monkey.retromusic.service.MusicService
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.koin.core.KoinComponent
import org.koin.core.get
import java.io.File
import java.io.IOException
import java.util.*
import java.util.regex.Pattern


object MusicUtil : KoinComponent {
    fun createShareSongFileIntent(song: Song, context: Context): Intent? {
        return try {
            Intent().setAction(Intent.ACTION_SEND).putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(
                    context,
                    context.applicationContext.packageName,
                    File(song.data)
                )
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setType("audio/*")
        } catch (e: IllegalArgumentException) {
            // TODO the path is most likely not like /storage/emulated/0/... but something like /storage/28C7-75B0/...
            e.printStackTrace()
            Toast.makeText(
                context,
                "Could not share this file, I'm aware of the issue.",
                Toast.LENGTH_SHORT
            ).show()
            Intent()
        }
    }

    fun buildInfoString(string1: String?, string2: String?): String {
        if (string1.isNullOrEmpty()) {
            return if (string2.isNullOrEmpty()) "" else string2
        }
        return if (string2.isNullOrEmpty()) if (string1.isNullOrEmpty()) "" else string1 else "$string1  •  $string2"
    }

    fun createAlbumArtFile(): File {
        return File(
            createAlbumArtDir(),
            System.currentTimeMillis().toString()
        )
    }

    private fun createAlbumArtDir(): File {
        val albumArtDir = File(Environment.getExternalStorageDirectory(), "/albumthumbs/")
        if (!albumArtDir.exists()) {
            albumArtDir.mkdirs()
            try {
                File(albumArtDir, ".nomedia").createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return albumArtDir
    }

    fun deleteAlbumArt(context: Context, albumId: Int) {
        val contentResolver = context.contentResolver
        val localUri = Uri.parse("content://media/external/audio/albumart")
        contentResolver.delete(ContentUris.withAppendedId(localUri, albumId.toLong()), null, null)
        contentResolver.notifyChange(localUri, null)
    }

    fun getArtistInfoString(
        context: Context,
        artist: Artist
    ): String {
        val albumCount = artist.albumCount
        val songCount = artist.songCount
        val albumString =
            if (albumCount == 1) context.resources.getString(R.string.album)
            else context.resources.getString(R.string.albums)
        val songString =
            if (songCount == 1) context.resources.getString(R.string.song)
            else context.resources.getString(R.string.songs)
        return "$albumCount $albumString • $songCount $songString"
    }

    //iTunes uses for example 1002 for track 2 CD1 or 3011 for track 11 CD3.
    //this method converts those values to normal tracknumbers
    fun getFixedTrackNumber(trackNumberToFix: Int): Int {
        return trackNumberToFix % 1000
    }

    fun getLyrics(song: Song): String? {
        var lyrics: String? = null
        val file = File(song.data)
        try {
            lyrics = AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (lyrics == null || lyrics.trim { it <= ' ' }.isEmpty() || !AbsSynchronizedLyrics
                .isSynchronized(lyrics)
        ) {
            val dir = file.absoluteFile.parentFile
            if (dir != null && dir.exists() && dir.isDirectory) {
                val format = ".*%s.*\\.(lrc|txt)"
                val filename = Pattern.quote(
                    FileUtil.stripExtension(file.name)
                )
                val songtitle = Pattern.quote(song.title)
                val patterns =
                    ArrayList<Pattern>()
                patterns.add(
                    Pattern.compile(
                        String.format(format, filename),
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    )
                )
                patterns.add(
                    Pattern.compile(
                        String.format(format, songtitle),
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    )
                )
                val files =
                    dir.listFiles { f: File ->
                        for (pattern in patterns) {
                            if (pattern.matcher(f.name).matches()) {
                                return@listFiles true
                            }
                        }
                        false
                    }
                if (files != null && files.size > 0) {
                    for (f in files) {
                        try {
                            val newLyrics =
                                FileUtil.read(f)
                            if (newLyrics != null && !newLyrics.trim { it <= ' ' }.isEmpty()) {
                                if (AbsSynchronizedLyrics.isSynchronized(newLyrics)) {
                                    return newLyrics
                                }
                                lyrics = newLyrics
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        return lyrics
    }

    fun getMediaStoreAlbumCoverUri(albumId: Int): Uri {
        val sArtworkUri =
            Uri.parse("content://media/external/audio/albumart")
        return ContentUris.withAppendedId(sArtworkUri, albumId.toLong())
    }


    fun getPlaylistInfoString(
        context: Context,
        songs: List<Song>
    ): String {
        val duration = getTotalDuration(songs)
        return buildInfoString(
            getSongCountString(context, songs.size),
            getReadableDurationString(duration)
        )
    }

    fun playlistInfoString(
        context: Context,
        songs: List<SongEntity>
    ): String {
        return getSongCountString(context, songs.size)
    }

    fun getReadableDurationString(songDurationMillis: Long): String? {
        var minutes = songDurationMillis / 1000 / 60
        val seconds = songDurationMillis / 1000 % 60
        return if (minutes < 60) {
            String.format(
                Locale.getDefault(),
                "%02d:%02d",
                minutes,
                seconds
            )
        } else {
            val hours = minutes / 60
            minutes = minutes % 60
            String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        }
    }

    fun getSectionName(musicMediaTitle: String?): String {
        var musicMediaTitle = musicMediaTitle
        return try {
            if (TextUtils.isEmpty(musicMediaTitle)) {
                return ""
            }
            musicMediaTitle = musicMediaTitle!!.trim { it <= ' ' }.toLowerCase()
            if (musicMediaTitle.startsWith("the ")) {
                musicMediaTitle = musicMediaTitle.substring(4)
            } else if (musicMediaTitle.startsWith("a ")) {
                musicMediaTitle = musicMediaTitle.substring(2)
            }
            if (musicMediaTitle.isEmpty()) {
                ""
            } else musicMediaTitle.substring(0, 1).toUpperCase()
        } catch (e: Exception) {
            ""
        }
    }

    fun getSongCountString(context: Context, songCount: Int): String {
        val songString = if (songCount == 1) context.resources
            .getString(R.string.song) else context.resources.getString(R.string.songs)
        return "$songCount $songString"
    }

    fun getSongFileUri(songId: Int): Uri {
        return ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId.toLong()
        )
    }

    fun getTotalDuration(songs: List<Song>): Long {
        var duration: Long = 0
        for (i in songs.indices) {
            duration += songs[i].duration
        }
        return duration
    }

    fun getYearString(year: Int): String {
        return if (year > 0) year.toString() else "-"
    }

    fun indexOfSongInList(songs: List<Song>, songId: Int): Int {
        for (i in songs.indices) {
            if (songs[i].id == songId) {
                return i
            }
        }
        return -1
    }

    fun insertAlbumArt(
        context: Context,
        albumId: Int,
        path: String?
    ) {
        val contentResolver = context.contentResolver
        val artworkUri =
            Uri.parse("content://media/external/audio/albumart")
        contentResolver.delete(ContentUris.withAppendedId(artworkUri, albumId.toLong()), null, null)
        val values = ContentValues()
        values.put("album_id", albumId)
        values.put("_data", path)
        contentResolver.insert(artworkUri, values)
        contentResolver.notifyChange(artworkUri, null)
    }

    fun isArtistNameUnknown(artistName: String?): Boolean {
        if (TextUtils.isEmpty(artistName)) {
            return false
        }
        if (artistName == Artist.UNKNOWN_ARTIST_DISPLAY_NAME) {
            return true
        }
        val tempName = artistName!!.trim { it <= ' ' }.toLowerCase()
        return tempName == "unknown" || tempName == "<unknown>"
    }

    fun isFavorite(context: Context, song: Song): Boolean {
        return PlaylistsUtil
            .doPlaylistContains(context, getFavoritesPlaylist(context).id.toLong(), song.id)
    }

    fun isFavoritePlaylist(
        context: Context,
        playlist: Playlist
    ): Boolean {
        return playlist.name != null && playlist.name == context.getString(R.string.favorites)
    }

    fun toggleFavorite(context: Context, song: Song) {
        if (isFavorite(context, song)) {
            PlaylistsUtil.removeFromPlaylist(context, song, getFavoritesPlaylist(context).id)
        } else {
            PlaylistsUtil.addToPlaylist(
                context, song, getOrCreateFavoritesPlaylist(context).id,
                false
            )
        }
        context.sendBroadcast(Intent(MusicService.FAVORITE_STATE_CHANGED))
    }

    private fun getFavoritesPlaylist(context: Context): Playlist {
        return RealPlaylistRepository(context.contentResolver).playlist(context.getString(R.string.favorites))
    }

    private fun getOrCreateFavoritesPlaylist(context: Context): Playlist {
        return RealPlaylistRepository(context.contentResolver).playlist(
            PlaylistsUtil.createPlaylist(
                context,
                context.getString(R.string.favorites)
            )
        )
    }

    fun deleteTracks(
        activity: FragmentActivity,
        songs: List<Song>,
        safUris: List<Uri>?,
        callback: Runnable?
    ) {
        val songRepository: SongRepository = get()
        val projection = arrayOf(
            BaseColumns._ID, MediaStore.MediaColumns.DATA
        )
        // Split the query into multiple batches, and merge the resulting cursors
        var batchStart = 0
        var batchEnd = 0
        val batchSize =
            1000000 / 10 // 10^6 being the SQLite limite on the query lenth in bytes, 10 being the max number of digits in an int, used to store the track ID
        val songCount = songs.size

        while (batchEnd < songCount) {
            batchStart = batchEnd

            val selection = StringBuilder()
            selection.append(BaseColumns._ID + " IN (")

            var i = 0
            while (i < batchSize - 1 && batchEnd < songCount - 1) {
                selection.append(songs[batchEnd].id)
                selection.append(",")
                i++
                batchEnd++
            }
            // The last element of a batch
            // The last element of a batch
            selection.append(songs[batchEnd].id)
            batchEnd++
            selection.append(")")

            try {
                val cursor = activity.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection.toString(),
                    null, null
                )
                if (cursor != null) {
                    // Step 1: Remove selected tracks from the current playlist, as well
                    // as from the album art cache
                    cursor.moveToFirst()
                    while (!cursor.isAfterLast) {
                        val id = cursor.getInt(0)
                        val song: Song = songRepository.song(id)
                        removeFromQueue(song)
                        cursor.moveToNext()
                    }

                    // Step 2: Remove selected tracks from the database
                    activity.contentResolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        selection.toString(), null
                    )
                    // Step 3: Remove files from card
                    cursor.moveToFirst()
                    var index = batchStart
                    while (!cursor.isAfterLast) {
                        val name = cursor.getString(1)
                        val safUri =
                            if (safUris == null || safUris.size <= index) null else safUris[index]
                        SAFUtil.delete(activity, name, safUri)
                        index++
                        cursor.moveToNext()
                    }
                    cursor.close()
                }
            } catch (ignored: SecurityException) {

            }
            activity.contentResolver.notifyChange(Uri.parse("content://media"), null)
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.deleted_x_songs, songCount),
                    Toast.LENGTH_SHORT
                )
                    .show()
                callback?.run()
            }
        }
    }
}