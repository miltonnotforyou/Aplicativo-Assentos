package com.example

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.io.FileOutputStream

// --- CLASSES DE DADOS ---

/**
 * Representa um usuário (foto/avatar procedural ou real) disponível para alocação no mapa.
 */
data class Player(
    val id: Int,
    val name: String,
    val role: String,
    val avatarColor: Color,
    val textColor: Color,
    val initials: String,
    val avatarShapeType: Int, // Diferentes tipos de cabelo e acessórios renders
    val photoUri: String? = null // Caminho ou URI da foto real do usuário
)

/**
 * Representa o estado individual de uma célula da planilha Excel.
 */
data class ExcelSeatCell(
    val visualRowIndex: Int,       // 0 a 5 correspondendo às linhas do Excel
    val colIndex: Int,             // coluna
    val columnName: String,        // Letra
    val seatNumber: Int?,          // Número do assento
    val isRestricted: Boolean,     // True se for um "Espaço Restrito" (símbolo Ø)
    val isCorridor: Boolean = false, // True se for espaço de corredor em azul
    val blockTitle: String         // Qual bloco do painel ele pertence
)

// --- MOCK DATA ---

val defaultPlayersList = listOf(
    Player(1, "Ana Silva", "Líder Admin", Color(0xFF6750A4), Color.White, "AS", 0),
    Player(2, "Carlos Souza", "Coordenador", Color(0xFF7D5260), Color.White, "CS", 1),
    Player(3, "Mariana Costa", "Suporte", Color(0xFF625B71), Color.White, "MC", 2),
    Player(4, "Bruno Alves", "Imprensa", Color(0xFFD0BCFF), Color(0xFF21005D), "BA", 3),
    Player(5, "Julia Lima", "Convidada Especial", Color(0xFFEADDFF), Color(0xFF21005D), "JL", 4),
    Player(6, "Pedro Rocha", "Segurança Chefe", Color(0xFFC2E7FF), Color(0xFF001D35), "PR", 5),
    Player(7, "Aline M.", "Secretária", Color(0xFFF2B8B5), Color(0xFF601410), "AM", 0)
)

// --- COMPOSABLE PRINCIPAL DA TELA ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcelSeatMapScreen(viewModel: SeatMapViewModel = viewModel()) {
    val focusManager = LocalFocusManager.current

    // Observe Room database state reactively and safely
    val seatOccupants by viewModel.seatOccupants.collectAsStateWithLifecycle()
    val playersList by viewModel.playersList.collectAsStateWithLifecycle()

    // Estado do jogador selecionado no Banco de Imagens
    var selectedPlayer by remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(playersList) {
        if (selectedPlayer == null && playersList.isNotEmpty()) {
            selectedPlayer = playersList.firstOrNull()
        }
    }

    // Modo borracha/limpeza rápida
    var isEraserMode by remember { mutableStateOf(false) }

    // Zoom da planilha (Fatores: 0.7x, 1.0x, 1.3x) para garantir usabilidade em telas menores
    var zoomScale by remember { mutableStateOf(1.0f) }

    // Busca rápida de poltronas ou pessoas
    var searchKeyword by remember { mutableStateOf("") }

    // Célula atualmente focada para exibição de detalhes
    var focusedCellInfo by remember { mutableStateOf<Pair<ExcelSeatCell, Player?>?>(null) }

    // Controle de exibição do diálogo de adição de novo usuário
    var showAddPlayerDialog by remember { mutableStateOf(false) }

    // Controle de exibição do painel lateral em modo landscape/tablet ("bloco de adicionar")
    var showSidebar by remember { mutableStateOf(true) }

    // Controle de exibição do painel inferior em modo retrato
    var showPortraitUserBank by remember { mutableStateOf(true) }

    // Controle de maximização da área de assentos
    var isMaximized by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    val jsonString = exportPlayersToJson(playersList)
                    outputStream.write(jsonString.toByteArray())
                }
                Toast.makeText(context, "Banco de dados exportado com sucesso!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val jsonString = inputStream.bufferedReader().use { reader -> reader.readText() }
                    val importedPlayers = parsePlayersFromJson(jsonString)
                    if (importedPlayers != null && importedPlayers.isNotEmpty()) {
                        viewModel.importPlayers(importedPlayers)
                        Toast.makeText(context, "Banco de dados importado: ${importedPlayers.size} usuários!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Arquivo JSON inválido ou vazio!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao importar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Informação de configuração de tela para adaptabilidade
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenWidth = configuration.screenWidthDp.dp
    val isTablet = screenWidth >= 600.dp
    val useSideBar = isLandscape || isTablet

    // Diálogo para adicionar usuário com foto
    if (showAddPlayerDialog) {
        var newPlayerName by remember { mutableStateOf("") }
        var newPlayerRole by remember { mutableStateOf("") }
        var newPlayerPhotoUri by remember { mutableStateOf<String?>(null) }

        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
            onResult = { uri ->
                if (uri != null) {
                    newPlayerPhotoUri = uri.toString()
                }
            }
        )

        AlertDialog(
            onDismissRequest = { showAddPlayerDialog = false },
            title = {
                Text(
                    text = "Adicionar Novo Usuário",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF6750A4)
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newPlayerName,
                        onValueChange = { newPlayerName = it },
                        label = { Text("Nome Completo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCAC4D0)
                        )
                    )

                    OutlinedTextField(
                        value = newPlayerRole,
                        onValueChange = { newPlayerRole = it },
                        label = { Text("Cargo / Função") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCAC4D0)
                        )
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "FOTO DO USUÁRIO:",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF49454F),
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Visualizador prévio ou avatar
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFFE8DEF8), shape = CircleShape)
                                .border(1.dp, Color(0xFF6750A4), CircleShape)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (newPlayerPhotoUri != null) {
                                AsyncImage(
                                    model = newPlayerPhotoUri,
                                    contentDescription = "Visualização da foto",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Sem Foto",
                                    tint = Color(0xFF6750A4),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE8DEF8),
                                contentColor = Color(0xFF6750A4)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (newPlayerPhotoUri != null) "Alterar Foto 📸" else "Escolher Foto 📸", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlayerName.isNotBlank()) {
                            val nextId = (playersList.maxOfOrNull { it.id } ?: 0) + 1
                            val initials = newPlayerName.trim().split(" ")
                                .filter { it.isNotEmpty() }
                                .take(2)
                                .map { it.first().uppercase() }
                                .joinToString("")

                            val persistedPhotoUri = newPlayerPhotoUri?.let { path ->
                                copyUriToInternalStorage(context, path)
                            }

                            val newPlayer = Player(
                                id = nextId,
                                name = newPlayerName,
                                role = if (newPlayerRole.isBlank()) "Membro" else newPlayerRole,
                                avatarColor = listOf(
                                    Color(0xFF6750A4), Color(0xFF7D5260), Color(0xFF625B71),
                                    Color(0xFF008080), Color(0xFF2C3E50), Color(0xFF8E44AD),
                                    Color(0xFF2E8B57), Color(0xFFD2691E), Color(0xFF4682B4)
                               ).random(),
                                textColor = Color.White,
                                initials = if (initials.isEmpty()) "XX" else initials,
                                avatarShapeType = (0..5).random(),
                                photoUri = persistedPhotoUri
                            )

                            viewModel.addPlayer(newPlayer)
                            selectedPlayer = newPlayer
                            isEraserMode = false
                            showAddPlayerDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Adicionar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPlayerDialog = false }) {
                    Text("Cancelar", color = Color(0xFF49454F))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Surface(
                color = Color(0xFFFEF7FF), // Material 3 Surface
                contentColor = Color(0xFF1D1B20), // MD3 onSurface
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFE8DEF8), shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Painel",
                            tint = Color(0xFF6750A4)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "GESTOR DE ASSENTOS",
                            color = Color(0xFF1D1B20),
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "Plenário & Tribuna Interativa",
                            color = Color(0xFF49454F),
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    // Seletor de Busca integrado para Tablet/Landscape no header
                    if (useSideBar) {
                        Button(
                            onClick = { showSidebar = !showSidebar },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showSidebar) Color(0xFFE8DEF8) else Color(0xFF6750A4),
                                contentColor = if (showSidebar) Color(0xFF6750A4) else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(
                                imageVector = if (showSidebar) Icons.Default.Clear else Icons.Default.Add,
                                contentDescription = if (showSidebar) "Ocultar Alocação" else "Menu de Alocação"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (showSidebar) "Ocultar Menu" else "Adicionar / Alocar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = { isMaximized = !isMaximized },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMaximized) Color(0xFFE8DEF8) else Color(0xFF6750A4),
                                contentColor = if (isMaximized) Color(0xFF6750A4) else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Text(
                                text = if (isMaximized) "Restaurar layout" else "Maximizar Assentos ⛶",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    } else {
                        // Portrait: Adicionar botão de toggle de banco de usuários
                        Button(
                            onClick = { showPortraitUserBank = !showPortraitUserBank },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showPortraitUserBank) Color(0xFFE8DEF8) else Color(0xFF6750A4),
                                contentColor = if (showPortraitUserBank) Color(0xFF6750A4) else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = if (showPortraitUserBank) Icons.Default.Clear else Icons.Default.Add,
                                contentDescription = if (showPortraitUserBank) "Ocultar Usuários" else "Exibir Usuários",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (showPortraitUserBank) "Ocultar Menu" else "Menu Usuários",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Botão de Refresh para redefinir o mapa
                    IconButton(onClick = {
                        viewModel.clearAllAssignments()
                        focusedCellInfo = null
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Limpar Tudo",
                            tint = Color(0xFF6750A4)
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!isMaximized) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .background(Color(0xFFF3F0F4))
                        .border(1.dp, Color(0xFFCAC4D0))
                ) {
                    if (!useSideBar && showPortraitUserBank) {
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "BANCO DE USUÁRIOS",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF49454F),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "+ Novo",
                                color = Color(0xFF6750A4),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { showAddPlayerDialog = true }
                            )
                        }

                        Surface(
                            checked = isEraserMode,
                            onCheckedChange = {
                                isEraserMode = it
                                if (it) selectedPlayer = null
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isEraserMode) Color(0xFFF2B8B5) else Color(0xFFE8DEF8),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = "Borracha ⌫",
                                    color = if (isEraserMode) Color(0xFF601410) else Color(0xFF21005D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Rolagem horizontal dos avatares para selecionar
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banco_imagens_lista"),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .clickable { showAddPlayerDialog = true }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(Color(0xFFE8DEF8), shape = CircleShape)
                                        .border(2.dp, Color(0xFF6750A4), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Adicionar",
                                        tint = Color(0xFF6750A4)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Adicionar",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4)
                                )
                            }
                        }

                        items(playersList) { player ->
                            val isSelected = selectedPlayer?.id == player.id && !isEraserMode
                            Column(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        isEraserMode = false
                                        selectedPlayer = player
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier.size(60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = if (isSelected) {
                                            Modifier
                                                .size(52.dp)
                                                .border(2.dp, Color(0xFF6750A4), CircleShape)
                                                .padding(2.dp)
                                        } else {
                                            Modifier
                                                .size(52.dp)
                                                .padding(2.dp)
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        InteractiveAvatar(
                                            player = player,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // Small delete button overlayed in top-right of selected avatar
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .align(Alignment.TopEnd)
                                                .background(Color(0xFFBA1A1A), CircleShape)
                                                .clickable {
                                                    viewModel.deletePlayer(player)
                                                    selectedPlayer = null
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Excluir Usuário",
                                                tint = Color.White,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = player.name.split(" ").firstOrNull() ?: player.name,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF6750A4) else Color(0xFF49454F),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    HorizontalDivider(color = Color(0xFFCAC4D0), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "ESTATÍSTICAS DO PAINEL",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF49454F),
                                fontSize = 11.sp
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = "Lugares: 125",
                                    fontSize = 11.sp,
                                    color = Color(0xFF49454F)
                                )
                                Text(
                                    text = "Ocupados: ${seatOccupants.size}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF6750A4),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Restritos: 7",
                                    fontSize = 11.sp,
                                    color = Color(0xFF49454F)
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                viewModel.fillInitialRandomAssignments()
                                focusedCellInfo = null
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Preencher Auto", fontSize = 11.sp, color = Color(0xFF6750A4))
                        }
                    }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color(0xFFF3F0F4))
                            .drawBehind {
                                drawLine(
                                    color = Color(0xFFCAC4D0),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx()
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.clickable { }
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFE8DEF8), shape = RoundedCornerShape(16.dp))
                                    .padding(horizontal = 18.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Início",
                                    tint = Color(0xFF1D192B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Início",
                                color = Color(0xFF1D192B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    createDocumentLauncher.launch("users_database.json")
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Exportar Banco",
                                tint = Color(0xFF49454F),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Exportar Banco",
                                color = Color(0xFF49454F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Importar Banco",
                                tint = Color(0xFF49454F),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Importar Banco",
                                color = Color(0xFF49454F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFFFEF7FF)
    ) { innerPadding ->
        if (useSideBar) {
            // LAYOUT RESPONSIVO PARA MEIO E EXPANDIDO (TABLETS E GIRO DE TELA DESKTOP)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Conteúdo Principal: Gráfico do Plenário / Grid de Assentos (Lado Esquerdo)
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                ) {
                    if (!isMaximized) {
                        StageLayoutBanners()
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFFFEF7FF))
                    ) {
                        val horizontalScrollState = rememberScrollState()
                        val verticalScrollState = rememberScrollState()

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                                .verticalScroll(verticalScrollState)
                                .padding(12.dp)
                        ) {
                            ExcelInteractiveSpreadsheet(
                                seatOccupants = seatOccupants,
                                selectedPlayer = selectedPlayer,
                                isEraserMode = isEraserMode,
                                zoomScale = zoomScale,
                                searchKeyword = searchKeyword,
                                focusedCellInfo = focusedCellInfo,
                                onCellFocused = { cell, player ->
                                    focusedCellInfo = Pair(cell, player)
                                },
                                onToggleSeat = { seatNo, playerToAssign ->
                                    if (playerToAssign == null) {
                                        viewModel.unassignSeat(seatNo)
                                    } else {
                                        viewModel.assignSeat(seatNo, playerToAssign)
                                    }
                                }
                            )
                        }

                        // Indicador de Ajuda
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "↔ Arrastar Plenário ↕",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Controle de Zoom Flutuante para perfeita interação em tablets
                        Surface(
                            color = Color.White.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(8.dp),
                            shadowElevation = 3.dp,
                            border = BorderStroke(1.dp, Color(0xFFCAC4D0)),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                TextButton(
                                    onClick = { if (zoomScale > 0.7f) zoomScale -= 0.15f },
                                    modifier = Modifier.width(32.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("-", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF6750A4))
                                }
                                Text(
                                    text = "${(zoomScale * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF49454F),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                TextButton(
                                    onClick = { if (zoomScale < 1.4f) zoomScale += 0.15f },
                                    modifier = Modifier.width(32.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("+", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF6750A4))
                                }
                            }
                        }
                    }
                }

                if (showSidebar && !isMaximized) {
                    // Divisor Visual Fino entre Grade e Sidebar
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Color(0xFFCAC4D0))
                    )

                    // Barra Lateral de Gerenciamento Dirigida (Rápida visualização e maior ergonomia)
                    Column(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFF3F0F4))
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "GERENCIADOR DE ALOCAÇÃO",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6750A4),
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )

                    // Card do usuário atualmente ativo para inserção rápida
                    val activeLabel = if (isEraserMode) "Borracha Ativada" else selectedPlayer?.name ?: "Nenhum Selecionado"
                    val activeSub = if (isEraserMode) "Clique em qualquer assento para desvincular" else selectedPlayer?.role ?: "Escolha um membro no banco abaixo"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isEraserMode) Color(0xFFF2B8B5) else Color(0xFFE8DEF8)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(44.dp)) {
                                if (isEraserMode) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF601410), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "⌫",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        )
                                    }
                                } else if (selectedPlayer != null) {
                                    InteractiveAvatar(
                                        player = selectedPlayer!!,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Vazio",
                                        tint = Color(0xFF6750A4)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = activeLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isEraserMode) Color(0xFF601410) else Color(0xFF1D192B),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = activeSub,
                                    fontSize = 10.sp,
                                    color = if (isEraserMode) Color(0xFF601410).copy(alpha = 0.8f) else Color(0xFF49454F),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Ações de gerenciamento rápidas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showAddPlayerDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                        ) {
                            Text("+ Novo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                isEraserMode = !isEraserMode
                                if (isEraserMode) selectedPlayer = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEraserMode) Color(0xFFF2B8B5) else Color(0xFFE8DEF8),
                                contentColor = if (isEraserMode) Color(0xFF601410) else Color(0xFF21005D)
                            )
                        ) {
                            Text(if (isEraserMode) "Lápis" else "Borracha", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.fillInitialRandomAssignments()
                            focusedCellInfo = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6750A4))
                    ) {
                        Text("Preencher Assentos Manual", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    HorizontalDivider(color = Color(0xFFCAC4D0))

                    // Estatísticas da Sessão
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "ESTATÍSTICAS DO MAPA",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF49454F),
                            fontSize = 11.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Total:", fontSize = 12.sp, color = Color(0xFF49454F))
                            Text("132 assentos", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20))
                        }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Ocupados:", fontSize = 12.sp, color = Color(0xFF49454F))
                            Text("${seatOccupants.size} ocupados", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6750A4))
                        }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Corredores:", fontSize = 12.sp, color = Color(0xFF49454F))
                            Text("18 divisões", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF49454F))
                        }
                    }

                    HorizontalDivider(color = Color(0xFFCAC4D0))

                    // Diretório Vertical de Usuários com nomes legíveis (Visual Limpo e Ergonômico M3)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "DIRETÓRIO DE USUÁRIOS",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF49454F),
                            fontSize = 11.sp
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            playersList.forEach { player ->
                                val isSelected = selectedPlayer?.id == player.id && !isEraserMode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFFE8DEF8) else Color.Transparent)
                                        .clickable {
                                            isEraserMode = false
                                            selectedPlayer = player
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(36.dp)) {
                                        InteractiveAvatar(
                                            player = player,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = player.name,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 12.sp,
                                            color = Color(0xFF1D1B20),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = player.role,
                                            fontSize = 10.sp,
                                            color = Color(0xFF49454F),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        IconButton(
                                            onClick = {
                                                viewModel.deletePlayer(player)
                                                selectedPlayer = null
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Excluir Usuário",
                                                tint = Color(0xFFBA1A1A),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        } else {
            // RETRATO MOBILE PADRÃO (Telas Normais sem rotação)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Caixa de ferramentas sem busca (visual limpo + maximizar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF3F0F4))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { isMaximized = !isMaximized },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMaximized) Color(0xFFE8DEF8) else Color(0xFF6750A4),
                            contentColor = if (isMaximized) Color(0xFF6750A4) else Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(
                            text = if (isMaximized) "Restaurar ⛶" else "Maximizar Mapa ⛶",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Botoes de Zoom compactos em Retrato
                    Row(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(8.dp))
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (zoomScale > 0.7f) zoomScale -= 0.15f },
                            modifier = Modifier.width(32.dp)
                        ) {
                            Text("-", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF6750A4))
                        }
                        Text(
                            text = "${(zoomScale * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF49454F),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        IconButton(
                            onClick = { if (zoomScale < 1.4f) zoomScale += 0.15f },
                            modifier = Modifier.width(32.dp)
                        ) {
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF6750A4))
                        }
                    }
                }

                if (!isMaximized) {
                    StageLayoutBanners()
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFFEF7FF))
                ) {
                    val horizontalScrollState = rememberScrollState()
                    val verticalScrollState = rememberScrollState()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScrollState)
                            .verticalScroll(verticalScrollState)
                            .padding(12.dp)
                    ) {
                        ExcelInteractiveSpreadsheet(
                            seatOccupants = seatOccupants,
                            selectedPlayer = selectedPlayer,
                            isEraserMode = isEraserMode,
                            zoomScale = zoomScale,
                            searchKeyword = searchKeyword,
                            focusedCellInfo = focusedCellInfo,
                            onCellFocused = { cell, player ->
                                focusedCellInfo = Pair(cell, player)
                            },
                            onToggleSeat = { seatNo, playerToAssign ->
                                if (playerToAssign == null) {
                                    viewModel.unassignSeat(seatNo)
                                } else {
                                    viewModel.assignSeat(seatNo, playerToAssign)
                                }
                            }
                        )
                    }

                    // Floating scroll helper
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "↔ Rolagem Livre ↕",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// --- TÍTULOS DO PALCO E BLOCOS (IGUAL AO VÍDEO E DIAGRAMA) ---

@Composable
fun StageLayoutBanners() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF6750A4)) // Roxo MD3 Lindo e profissional
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Título Principal
        Text(
            text = "① PLANO ABERTO - PALCO",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "CÂMERA 1 • PAINEL DE ASSENTOS",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE8DEF8),
            fontSize = 9.sp,
            textAlign = TextAlign.Center
        )
    }
}

// --- A SPREADSHEET EXCEL COM GRADE DE INTERAÇÃO ---

@Composable
fun ExcelInteractiveSpreadsheet(
    seatOccupants: Map<Int, Player>,
    selectedPlayer: Player?,
    isEraserMode: Boolean,
    zoomScale: Float,
    searchKeyword: String,
    focusedCellInfo: Pair<ExcelSeatCell, Player?>?,
    onCellFocused: (ExcelSeatCell, Player?) -> Unit,
    onToggleSeat: (Int, Player?) -> Unit
) {
    // Definimos os tamanhos base calculados de acordo com a escala de zoom
    val baseCellWidth = 46.dp * zoomScale
    val baseCellHeight = 46.dp * zoomScale

    val indexColWidth = 32.dp * zoomScale
    val indexRowHeight = 22.dp * zoomScale

    // Temos exatamente 25 colunas e 6 linhas
    val columnsCount = 25
    val rowsCount = 6

    val columnNames = listOf(
        "A", "B", "C", "D", "E", // Block 1
        "F", // Corridor 1
        "G", "H", "I", "J", "K", "L", // Block 2
        "M", // Corridor 2
        "N", "O", "P", "Q", "R", "S", // Block 3
        "T", // Corridor 3
        "U", "V", "W", "X", "Y" // Block 4
    )

    Column(
        modifier = Modifier
            .background(Color.White)
            .border(1.dp, Color(0xFFCAC4D0))
    ) {
        // --- 1. CABEÇALHOS DO VENUE (Agrupamento de Blocos em cima das colunas) ---
        Row {
            // Caixa vazia do indicador
            Box(modifier = Modifier.width(indexColWidth))

            // Banners De Agrupamento de Linhas/Colunas
            VenueHeaderBlock(
                title = "③ CADEIRA (ESQ.)",
                width = baseCellWidth * 5,
                color = Color(0xFF625B71)
            )
            // Corridor 1 spacer
            Box(modifier = Modifier.width(baseCellWidth).height(30.dp).background(Color(0xFF1F2B48)))

            VenueHeaderBlock(
                title = "② TRIBUNA",
                width = baseCellWidth * 13, // Covers block 2, corridor 2, block 3
                color = Color(0xFF6750A4)
            )
            // Corridor 3 spacer
            Box(modifier = Modifier.width(baseCellWidth).height(30.dp).background(Color(0xFF1F2B48)))

            VenueHeaderBlock(
                title = "④ MESA (DIR.)",
                width = baseCellWidth * 5,
                color = Color(0xFF7D5260)
            )
        }

        // --- 2. CABEÇALHO ALFABÉTICO DAs COLUNAS DO EXCEL (A-Y) ---
        Row {
            // Canto Superior Esquerdo Vazio da Planilha Excel
            Box(
                modifier = Modifier
                    .size(width = indexColWidth, height = indexRowHeight)
                    .background(Color(0xFFF3F0F4))
                    .border(0.5.dp, Color(0xFFCAC4D0)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Ajuda",
                    modifier = Modifier.size(10.dp),
                    tint = Color(0xFF6750A4)
                )
            }

            // Letras das Colunas
            for (colIdx in 0 until columnsCount) {
                val letter = columnNames.getOrElse(colIdx) { "?" }
                Box(
                    modifier = Modifier
                        .size(width = baseCellWidth, height = indexRowHeight)
                        .background(Color(0xFFF3F0F4))
                        .border(width = 0.5.dp, color = Color(0xFFCAC4D0)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter,
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Bold,
                        fontSize = (11 * zoomScale).coerceAtLeast(8f).sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // --- 3. CONTEÚDO DAS CÉLULAS JUNTAMENTE AOS NÚMEROS DAS LINHAS (1 A 6) ---
        for (rowIdx in 0 until rowsCount) {
            Row {
                // Cabeçalho de Linha Numérica do Excel (1 a 6)
                Box(
                    modifier = Modifier
                        .size(width = indexColWidth, height = baseCellHeight)
                        .background(Color(0xFFF3F0F4))
                        .border(width = 0.5.dp, color = Color(0xFFCAC4D0)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${rowIdx + 1}",
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Bold,
                        fontSize = (11 * zoomScale).coerceAtLeast(8f).sp,
                        textAlign = TextAlign.Center
                    )
                }

                // Corpo das Células correspondentes da Coluna
                for (colIdx in 0 until columnsCount) {
                    val columnName = columnNames.getOrElse(colIdx) { "" }

                    // Obtém se é poltrona, número e se é espaço proibido/restrito
                    val (seatNumber, isRestricted) = getSeatNumberAndRestriction(rowIdx, colIdx)

                    val isCorridor = colIdx == 5 || colIdx == 12 || colIdx == 19

                    val blockTitle = when (colIdx) {
                        in 0..4 -> "Cadeira (Lado Esquerdo)"
                        5 -> "Corredor 1"
                        in 6..11 -> "Tribuna (Lado Esquerdo)"
                        12 -> "Corredor 2"
                        in 13..18 -> "Tribuna (Lado Direito)"
                        19 -> "Corredor 3"
                        else -> "Mesa (Lado Direito)"
                    }

                    val cell = ExcelSeatCell(
                        visualRowIndex = rowIdx,
                        colIndex = colIdx,
                        columnName = columnName,
                        seatNumber = seatNumber,
                        isRestricted = isRestricted,
                        isCorridor = isCorridor,
                        blockTitle = blockTitle
                    )

                    val occupant = seatNumber?.let { seatOccupants[it] }

                    // Verifica se o termo pesquisado bate com o assento ou com o ocupante para dar foco especial
                    val isSearchMatch = searchKeyword.isNotEmpty() && (
                        (seatNumber != null && seatNumber.toString() == searchKeyword) ||
                        (occupant != null && occupant.name.contains(searchKeyword, ignoreCase = true)) ||
                        (occupant != null && occupant.role.contains(searchKeyword, ignoreCase = true))
                    )

                    val isFocused = focusedCellInfo?.first?.visualRowIndex == rowIdx &&
                                    focusedCellInfo.first.colIndex == colIdx

                    ExcelGridCell(
                        cell = cell,
                        occupant = occupant,
                        isFocused = isFocused,
                        isSearchMatch = isSearchMatch,
                        zoomScale = zoomScale,
                        modifier = Modifier.size(width = baseCellWidth, height = baseCellHeight),
                        onClick = {
                            if (cell.isCorridor) return@ExcelGridCell
                            
                            onCellFocused(cell, occupant)

                            if (cell.isRestricted || cell.seatNumber == null) return@ExcelGridCell

                            if (isEraserMode) {
                                // Apaga o usuário do assento
                                onToggleSeat(cell.seatNumber, null)
                            } else {
                                if (occupant != null) {
                                    // Comportamento do Vídeo: Segundo clique limpa a foto e volta para número
                                    onToggleSeat(cell.seatNumber, null)
                                    onCellFocused(cell, null)
                                } else {
                                    // Primeiro clique no vazio atribui a foto ativa
                                    if (selectedPlayer != null) {
                                        onToggleSeat(cell.seatNumber, selectedPlayer)
                                        onCellFocused(cell, selectedPlayer)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// --- CÉLULA INDIVIDUAL DA PLANILHA EXCEL E LÓGICA DE VISUALIZAÇÃO ---

@Composable
fun ExcelGridCell(
    cell: ExcelSeatCell,
    occupant: Player?,
    isFocused: Boolean,
    isSearchMatch: Boolean,
    zoomScale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Definimos a cor do conteúdo e fundo da célula simulando o Microsoft Excel
    val backgroundColor = when {
        cell.isCorridor -> Color(0xFF2196F3)   // Corredor em azul conforme solicitado
        cell.isRestricted -> Color(0xFFD9D9D9) // Cinza escuro representativo dos restricted
        isSearchMatch -> Color(0xFFFFF2CC)    // Amarelo fluorescente de "Achado" na busca
        else -> Color.White                   // Branco padrão
    }

    // Se o assento selecionado estiver com foco, adiciona borda roxa de "Geometric Balance"
    val borderStrokeColor = when {
        isFocused -> Color(0xFF6750A4)      // Roxo MD3 oficial do tema
        isSearchMatch -> Color(0xFFEDB700)  // Borda de foco da busca (amarela)
        else -> Color(0xFFCAC4D0)           // Cinza padrão fino M3
    }

    val borderWidth = if (isFocused || isSearchMatch) 2.dp else 0.5.dp

    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(width = borderWidth, color = borderStrokeColor)
            .clickable(enabled = !cell.isCorridor) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (cell.isCorridor) {
            // Corredor azul - Vazio
        } else if (cell.isRestricted) {
            // Símbolo do Espaço Proibido / Restrito (Visual de diagonal no vídeo)
            Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                // Desenha o símbolo de área restrita (Ø)
                drawCircle(
                    color = Color(0xFF7F8C8D),
                    radius = (size.minDimension * 0.25f),
                    style = Stroke(width = 2f)
                )
                drawLine(
                    color = Color(0xFF7F8C8D),
                    start = Offset(center.x - size.minDimension * 0.2f, center.y + size.minDimension * 0.2f),
                    end = Offset(center.x + size.minDimension * 0.2f, center.y - size.minDimension * 0.2f),
                    strokeWidth = 2f
                )
            }
        } else {
            if (occupant != null) {
                // ESTADO OCUPADO: Visual em conformidade com Geometric Balance:
                // Um container arredondado lavanda (#E8DEF8) com uma borda roxa suave que aloja o avatar central
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.5.dp)
                        .background(Color(0xFFE8DEF8), shape = RoundedCornerShape(4.dp))
                        .border(width = 0.5.dp, color = Color(0xFF6750A4).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    InteractiveAvatar(
                        player = occupant,
                        modifier = Modifier
                            .size((32 * zoomScale).dp)
                            .shadow(0.5.dp, CircleShape),
                        isGridMode = true,
                        zoomScale = zoomScale
                    )

                    // Small high-contrast seat number overlay at top-start
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(1.dp)
                            .background(Color.White.copy(alpha = 0.85f), shape = RoundedCornerShape(2.dp))
                            .padding(horizontal = 2.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            text = "${cell.seatNumber}",
                            color = Color(0xFF1D1B20),
                            fontWeight = FontWeight.Black,
                            fontSize = (7 * zoomScale).coerceAtLeast(5f).sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                // ESTADO LIVRE: Exibe o número original do assento centralizado
                Text(
                    text = "${cell.seatNumber}",
                    color = Color(0xFF333333),
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * zoomScale).coerceAtLeast(8f).sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// --- PORTAL DE AVATARS COM DETALHES PROCEDURAIS E CORES SELECIONADAS ---

@Composable
fun InteractiveAvatar(
    player: Player,
    modifier: Modifier = Modifier,
    isGridMode: Boolean = false,
    zoomScale: Float = 1.0f
) {
    var isLoadFailed by remember(player.photoUri) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .background(player.avatarColor, shape = CircleShape)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (player.photoUri != null && !isLoadFailed) {
            val imageModel = if (player.photoUri.startsWith("/")) {
                java.io.File(player.photoUri)
            } else {
                player.photoUri
            }
            AsyncImage(
                model = imageModel,
                contentDescription = player.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                onError = { isLoadFailed = true }
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Paleta de cores de tons de pele procedurais baseados no ID do usuário
            val skinColor = when (player.id % 4) {
                0 -> Color(0xFFFFDBAC) // Claro
                1 -> Color(0xFFF1C27D) // Pardo/Bronzeado
                2 -> Color(0xFFC68642) // Escuro
                else -> Color(0xFFE0AC69) // Moreno
            }

            // Tons de cabelo correspondentes
            val hairColor = when (player.avatarShapeType) {
                0 -> Color(0xFF1E1E24) // Preto
                1 -> Color(0xFFEDC15A) // Loiro
                2 -> Color(0xFFA64B2A) // Ruivo
                3 -> Color(0xFF422E1A) // Castanho
                4 -> Color(0xFF7F8C8D) // Grisalho
                else -> Color(0xFF2C3E50) // Moderno escuro
            }

            // Desenha o Pescoço
            val neckWidth = width * 0.24f
            val neckHeight = height * 0.22f
            drawRect(
                color = skinColor.copy(alpha = 0.9f),
                topLeft = Offset((width - neckWidth) / 2f, height * 0.55f),
                size = Size(neckWidth, neckHeight)
            )

            // Desenha a Face Redonda
            val faceRadius = width * 0.34f
            val faceCenterRawY = height * 0.46f
            drawCircle(
                color = skinColor,
                radius = faceRadius,
                center = Offset(width / 2f, faceCenterRawY)
            )

            // Desenha Cabelo com base nas especificações do jogador
            when (player.avatarShapeType) {
                0 -> {
                    // Cabelo Curto com Franja
                    drawArc(
                        color = hairColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = Offset(width / 2f - faceRadius, faceCenterRawY - faceRadius * 1.05f),
                        size = Size(faceRadius * 2f, faceRadius * 1.3f)
                    )
                }
                1 -> {
                    // Cabelo Alto / Afro
                    drawCircle(
                        color = hairColor,
                        radius = faceRadius * 0.85f,
                        center = Offset(width / 2f, faceCenterRawY - faceRadius * 0.65f)
                    )
                }
                2 -> {
                    // Cabelo Longo caido nos lados
                    drawCircle(
                        color = hairColor,
                        radius = faceRadius * 0.45f,
                        center = Offset(width / 2f - faceRadius * 0.8f, faceCenterRawY)
                    )
                    drawCircle(
                        color = hairColor,
                        radius = faceRadius * 0.45f,
                        center = Offset(width / 2f + faceRadius * 0.8f, faceCenterRawY)
                    )
                    drawArc(
                        color = hairColor,
                        startAngle = 160f,
                        sweepAngle = 220f,
                        useCenter = true,
                        topLeft = Offset(width / 2f - faceRadius * 1.05f, faceCenterRawY - faceRadius * 1.05f),
                        size = Size(faceRadius * 2.1f, faceRadius * 1.2f)
                    )
                }
                3 -> {
                    // Cabelo Espetado / Spiky
                    val path = Path().apply {
                        moveTo(width / 2f - faceRadius * 0.9f, faceCenterRawY - faceRadius * 0.4f)
                        lineTo(width / 2f - faceRadius * 0.7f, faceCenterRawY - faceRadius * 1.2f)
                        lineTo(width / 2f - faceRadius * 0.3f, faceCenterRawY - faceRadius * 0.7f)
                        lineTo(width / 2f, faceCenterRawY - faceRadius * 1.3f)
                        lineTo(width / 2f + faceRadius * 0.3f, faceCenterRawY - faceRadius * 0.7f)
                        lineTo(width / 2f + faceRadius * 0.7f, faceCenterRawY - faceRadius * 1.2f)
                        lineTo(width / 2f + faceRadius * 0.9f, faceCenterRawY - faceRadius * 0.4f)
                        close()
                    }
                    drawPath(path = path, color = hairColor)
                }
                4 -> {
                    // Touca vermelha esporte
                    drawCircle(
                        color = hairColor,
                        radius = faceRadius * 0.85f,
                        center = Offset(width / 2f, faceCenterRawY - faceRadius * 0.55f)
                    )
                    drawRoundRect(
                        color = Color(0xFFC0392B),
                        topLeft = Offset(width / 2f - faceRadius * 0.88f, faceCenterRawY - faceRadius * 0.85f),
                        size = Size(faceRadius * 1.76f, faceRadius * 0.28f),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }
                else -> {
                    // Visual Careca com óculos e cavanhaque de segurança
                    drawRect(
                        color = Color(0xFF2C3E50),
                        topLeft = Offset(width / 2f - faceRadius * 0.25f, faceCenterRawY + faceRadius * 0.5f),
                        size = Size(faceRadius * 0.5f, faceRadius * 0.3f)
                    )
                }
            }

            // Olhos - dois pequenos círculos pretos
            val eyeOffsetY = faceCenterRawY - faceRadius * 0.12f
            val eyeHorizOffset = faceRadius * 0.35f
            drawCircle(
                color = Color(0xFF2C3E50),
                radius = faceRadius * 0.12f,
                center = Offset(width / 2f - eyeHorizOffset, eyeOffsetY)
            )
            drawCircle(
                color = Color(0xFF2C3E50),
                radius = faceRadius * 0.12f,
                center = Offset(width / 2f + eyeHorizOffset, eyeOffsetY)
            )

            // Simula óculos divertidos nos IDs pares
            if (player.id % 2 == 0) {
                // Armação de óculos circular estilosa
                val glassRadius = faceRadius * 0.26f
                drawCircle(
                    color = Color.Black.copy(alpha = 0.1f),
                    radius = glassRadius,
                    center = Offset(width / 2f - eyeHorizOffset, eyeOffsetY)
                )
                drawCircle(
                    color = Color(0xFFE67E22), // Laranja vibrante
                    radius = glassRadius,
                    center = Offset(width / 2f - eyeHorizOffset, eyeOffsetY),
                    style = Stroke(width = 2f)
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.1f),
                    radius = glassRadius,
                    center = Offset(width / 2f + eyeHorizOffset, eyeOffsetY)
                )
                drawCircle(
                    color = Color(0xFFE67E22),
                    radius = glassRadius,
                    center = Offset(width / 2f + eyeHorizOffset, eyeOffsetY),
                    style = Stroke(width = 2f)
                )
                // Ponte entre as lentes
                drawLine(
                    color = Color(0xFFE67E22),
                    start = Offset(width / 2f - eyeHorizOffset + glassRadius, eyeOffsetY),
                    end = Offset(width / 2f + eyeHorizOffset - glassRadius, eyeOffsetY),
                    strokeWidth = 2f
                )
            }

            // Sorriso (Semicírculo curvado)
            val mouthWidth = faceRadius * 0.44f
            val mouthHeight = faceRadius * 0.25f
            drawArc(
                color = Color(0xFFC0392B),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(width / 2f - mouthWidth / 2f, faceCenterRawY + faceRadius * 0.15f),
                size = Size(mouthWidth, mouthHeight),
                style = Stroke(width = 3.5f)
            )
        }
    }

        // Se estivermos na lista inferior (modo não grid), renderiza as iniciais maiores para legibilidade
        if (!isGridMode) {
            Text(
                text = player.initials,
                fontWeight = FontWeight.Bold,
                color = player.textColor,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp),
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(1f, 1f),
                        blurRadius = 1f
                    )
                )
            )
        } else {
            // Se estiver no grid da planilha Excel, coloca as iniciais em uma cor bem contrastada por cima
            Text(
                text = player.initials,
                fontWeight = FontWeight.Black,
                color = Color.White,
                fontSize = (7 * zoomScale).coerceAtLeast(5f).sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp),
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = Offset(1f, 1f),
                        blurRadius = 1.5f
                    )
                )
            )
        }
    }
}

// --- HEADER DO PAINEL DE ASSENTOS / CADERAS ---

@Composable
fun VenueHeaderBlock(
    title: String,
    width: androidx.compose.ui.unit.Dp,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(width = width, height = 30.dp)
            .background(color)
            .border(width = 0.5.dp, color = Color.White.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// --- FÓRMULA MOCK DE SUPORTE PARA CALCULAR A ESTRUTURA ---

fun getSeatNumberAndRestriction(rowIdx: Int, colIdx: Int): Pair<Int?, Boolean> {
    // Check if column is a corridor:
    if (colIdx == 5 || colIdx == 12 || colIdx == 19) {
        return Pair(null, false) // Not restricted in the sense of blocked seat, but it's a corridor
    }

    // Convert colIdx to seat index within the row (0..21)
    val seatCol = when {
        colIdx < 5 -> colIdx
        colIdx < 12 -> colIdx - 1
        colIdx < 19 -> colIdx - 2
        else -> colIdx - 3
    }

    // Row index 0 is top (Row 6), Row index 5 is bottom (Row 1)
    val seatRow = 5 - rowIdx // seatRow 0 is Row 1, seatRow 5 is Row 6
    
    val baseNumber = (seatRow * 22) + seatCol + 1

    if (baseNumber <= 89) {
        return Pair(baseNumber, false)
    } else {
        // We skip 90..99 (which is 10 numbers)
        // So any seat number above 89 is shifted by +10
        return Pair(baseNumber + 10, false)
    }
}

fun copyUriToInternalStorage(context: Context, uriString: String): String? {
    return try {
        val uri = Uri.parse(uriString)
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val fileName = "user_photo_${System.currentTimeMillis()}.jpg"
        val file = File(context.filesDir, fileName)
        val outputStream = FileOutputStream(file)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun exportPlayersToJson(players: List<Player>): String {
    val jsonArray = JSONArray()
    for (player in players) {
        val jsonObject = JSONObject()
        jsonObject.put("id", player.id)
        jsonObject.put("name", player.name)
        jsonObject.put("role", player.role)
        jsonObject.put("avatarColor", player.avatarColor.toArgb())
        jsonObject.put("textColor", player.textColor.toArgb())
        jsonObject.put("initials", player.initials)
        jsonObject.put("avatarShapeType", player.avatarShapeType)
        jsonObject.put("photoUri", player.photoUri ?: JSONObject.NULL)
        jsonArray.put(jsonObject)
    }
    return jsonArray.toString(4)
}

fun parsePlayersFromJson(jsonStr: String): List<Player>? {
    return try {
        val jsonArray = JSONArray(jsonStr)
        val list = mutableListOf<Player>()
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val id = jsonObject.optInt("id", 0)
            val name = jsonObject.optString("name", "")
            val role = jsonObject.optString("role", "")
            val avatarColorArgb = jsonObject.optInt("avatarColor", -983041) // Default gray etc
            val textColorArgb = jsonObject.optInt("textColor", -1) // White
            val initials = jsonObject.optString("initials", "XX")
            val avatarShapeType = jsonObject.optInt("avatarShapeType", 0)
            val photoUri = if (jsonObject.isNull("photoUri")) null else jsonObject.optString("photoUri")
            
            list.add(
                Player(
                    id = id,
                    name = name,
                    role = role,
                    avatarColor = Color(avatarColorArgb),
                    textColor = Color(textColorArgb),
                    initials = initials,
                    avatarShapeType = avatarShapeType,
                    photoUri = photoUri
                )
            )
        }
        list
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

