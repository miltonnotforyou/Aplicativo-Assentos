package com.example

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

// --- ROOM ENTITIES ---

@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val role: String,
    val avatarColorArgb: Int,
    val textColorArgb: Int,
    val initials: String,
    val avatarShapeType: Int,
    val photoUri: String? = null
)

@Entity(tableName = "seat_occupants")
data class SeatOccupantEntity(
    @PrimaryKey val seatNumber: Int,
    val playerId: Int
)

// --- MAPPERS ---

fun PlayerEntity.toPlayer(): Player = Player(
    id = id,
    name = name,
    role = role,
    avatarColor = Color(avatarColorArgb),
    textColor = Color(textColorArgb),
    initials = initials,
    avatarShapeType = avatarShapeType,
    photoUri = photoUri
)

fun Player.toEntity(): PlayerEntity = PlayerEntity(
    id = id,
    name = name,
    role = role,
    avatarColorArgb = avatarColor.toArgb(),
    textColorArgb = textColor.toArgb(),
    initials = initials,
    avatarShapeType = avatarShapeType,
    photoUri = photoUri
)

// --- ROOM DAO ---

@Dao
interface SeatMapDao {
    @Query("SELECT * FROM players ORDER BY id ASC")
    fun getAllPlayersFlow(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players ORDER BY id ASC")
    suspend fun getAllPlayers(): List<PlayerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayer(player: PlayerEntity): Long

    @Delete
    suspend fun deletePlayer(player: PlayerEntity)

    @Query("SELECT * FROM seat_occupants")
    fun getAllSeatOccupantsFlow(): Flow<List<SeatOccupantEntity>>

    @Query("SELECT * FROM seat_occupants")
    suspend fun getAllSeatOccupants(): List<SeatOccupantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeatOccupant(occupant: SeatOccupantEntity)

    @Query("DELETE FROM seat_occupants WHERE seatNumber = :seatNumber")
    suspend fun deleteSeatOccupant(seatNumber: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayers(players: List<PlayerEntity>)

    @Query("DELETE FROM players")
    suspend fun deleteAllPlayers()

    @Query("DELETE FROM seat_occupants")
    suspend fun deleteAllSeatOccupants()
}

// --- ROOM DATABASE ---

@Database(entities = [PlayerEntity::class, SeatOccupantEntity::class], version = 1, exportSchema = false)
abstract class SeatMapDatabase : RoomDatabase() {
    abstract fun seatMapDao(): SeatMapDao

    companion object {
        @Volatile
        private var INSTANCE: SeatMapDatabase? = null

        fun getDatabase(context: Context): SeatMapDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SeatMapDatabase::class.java,
                    "seat_map_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- REPOSITORY CONTROLLING PERSISTENCE ---

class SeatMapRepository(private val dao: SeatMapDao) {
    
    val allPlayers: Flow<List<Player>> = dao.getAllPlayersFlow().map { list ->
        list.map { it.toPlayer() }
    }

    val seatOccupants: Flow<Map<Int, Player>> = combine(
        dao.getAllSeatOccupantsFlow(),
        dao.getAllPlayersFlow()
    ) { occupants, players ->
        val playersMap = players.associate { it.id to it.toPlayer() }
        occupants.associate { occupant ->
            occupant.seatNumber to (playersMap[occupant.playerId] ?: defaultPlayersList[0])
        }
    }

    suspend fun ensureDefaultPlayers() {
        val count = dao.getAllPlayers().size
        if (count == 0) {
            defaultPlayersList.forEach { player ->
                dao.insertPlayer(player.toEntity())
            }
        }
    }

    suspend fun addPlayer(player: Player): Long {
        return dao.insertPlayer(player.toEntity())
    }

    suspend fun deletePlayer(player: Player) {
        dao.deletePlayer(player.toEntity())
    }

    suspend fun assignSeat(seatNumber: Int, player: Player) {
        dao.insertSeatOccupant(SeatOccupantEntity(seatNumber, player.id))
    }

    suspend fun unassignSeat(seatNumber: Int) {
        dao.deleteSeatOccupant(seatNumber)
    }

    suspend fun clearAllAssignments() {
        dao.deleteAllSeatOccupants()
    }

    suspend fun importPlayers(players: List<Player>) {
        dao.deleteAllSeatOccupants()
        dao.deleteAllPlayers()
        dao.insertPlayers(players.map { it.toEntity() })
    }
}
