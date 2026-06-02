package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class SeatMapViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: SeatMapRepository

    val playersList: StateFlow<List<Player>>
    val seatOccupants: StateFlow<Map<Int, Player>>

    init {
        val database = SeatMapDatabase.getDatabase(application)
        val dao = database.seatMapDao()
        repository = SeatMapRepository(dao)

        playersList = repository.allPlayers.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = defaultPlayersList
        )

        seatOccupants = repository.seatOccupants.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureDefaultPlayers()
        }
    }

    fun addPlayer(player: Player) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addPlayer(player)
        }
    }

    fun deletePlayer(player: Player) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePlayer(player)
        }
    }

    fun assignSeat(seatNumber: Int, player: Player) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.assignSeat(seatNumber, player)
        }
    }

    fun unassignSeat(seatNumber: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.unassignSeat(seatNumber)
        }
    }

    fun clearAllAssignments() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllAssignments()
        }
    }
    
    fun fillInitialRandomAssignments() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllAssignments()
            val players = repository.allPlayers.stateIn(viewModelScope).value.ifEmpty { defaultPlayersList }
            val seats = (1..142).filter { it !in 90..99 }.shuffled()
            seats.take(45).forEach { seatNo ->
                repository.assignSeat(seatNo, players.random())
            }
        }
    }

    fun importPlayers(players: List<Player>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.importPlayers(players)
        }
    }
}
