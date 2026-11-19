package com.cnife.listenreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ResourceEntity::class,
        SegmentEntity::class,
        AudioTrackEntity::class,
        AudioClipEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ListenReaderDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {
        private const val NAME = "listen-reader.db"

        fun build(context: Context): ListenReaderDatabase =
            Room.databaseBuilder(context.applicationContext, ListenReaderDatabase::class.java, NAME)
                .build()
    }
}
