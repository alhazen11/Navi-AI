package com.apps.naviai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.database.RouteEntity
import com.apps.naviai.database.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedRoutesViewModel @Inject constructor(
    private val routeRepository: RouteRepository
) : ViewModel() {

    val routes: StateFlow<List<RouteEntity>> = routeRepository.observeRoutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteRoute(routeId: Long) = viewModelScope.launch { routeRepository.deleteRoute(routeId) }

    fun renameRoute(routeId: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { routeRepository.renameRoute(routeId, newName.trim()) }
    }
}
