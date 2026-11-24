package com.vault.recover

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "items")
data class Item(
    @PrimaryKey val id: String,
    val type: String,          // FILE, DEEP, CARVE, MAIL, ZIP, WA
    val name: String,
    val originalPath: String,
    val backupPath: String,
    val size: Long,
    val backedUpAt: Long,
    val deletedAt: Long?
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY COALESCE(deletedAt, backedUpAt) DESC")
    fun all(): Flow<List<Item>>

    @Query("SELECT * FROM items")
    suspend fun snapshot(): List<Item>

    @Query("SELECT id FROM items")
    suspend fun ids(): List<String>

    @Query("SELECT originalPath || '|' || size FROM items")
    suspend fun pathSizes(): List<String>

    @Query("SELECT * FROM items WHERE deletedAt IS NULL")
    suspend fun live(): List<Item>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(i: Item)

    @Query("UPDATE items SET deletedAt = :t WHERE id = :id")
    suspend fun markDeleted(id: String, t: Long)

    @Query("UPDATE items SET deletedAt = NULL WHERE id = :id")
    suspend fun markRestored(id: String)

    @Query("UPDATE items SET originalPath = :p, deletedAt = NULL WHERE id = :id")
    suspend fun relocate(id: String, p: String)

    @Query("UPDATE items SET backupPath = :p WHERE id = :id")
    suspend fun setBackup(id: String, p: String)

    @Delete
    suspend fun delete(i: Item)
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "vault.db")
                .build().also { inst = it }
        }
    }
}
