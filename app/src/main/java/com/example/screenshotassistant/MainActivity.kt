package com.example.screenshotassistant

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// 1. DATA PRESENTATION MODEL FOR SCREENSHOTS
// ==========================================
data class Screenshot(
    val id: Long = 0L,
    val title: String,
    val notes: String,
    val tags: String,                  
    val timestamp: Long = System.currentTimeMillis(),
    val isBookmarked: Boolean = false,
    val extractedText: String,          
    val templateId: Int                
)

// ==========================================
// 2. NATIVE SQLiteOpenHelper DATABASE BRIDGE
// ==========================================
class ScreenshotDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SCREENSHOTS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_NOTES TEXT,
                $COLUMN_TAGS TEXT,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_IS_BOOKMARKED INTEGER DEFAULT 0,
                $COLUMN_EXTRACTED_TEXT TEXT,
                $COLUMN_TEMPLATE_ID INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        seedMockScreenshots(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SCREENSHOTS")
        onCreate(db)
    }

    private fun seedMockScreenshots(db: SQLiteDatabase) {
        val seed1 = ContentValues().apply {
            put(COLUMN_TITLE, "Stripe Payout Confirmation")
            put(COLUMN_NOTES, "Official weekly payout generated to bank account ending in 9811.")
            put(COLUMN_TAGS, "Finance, Invoice")
            put(COLUMN_TIMESTAMP, System.currentTimeMillis() - 7200000)
            put(COLUMN_IS_BOOKMARKED, 1)
            put(COLUMN_EXTRACTED_TEXT, "STRIPE SYSTEM INC\nPAYMENT ID: ch_7A82B\nAMOUNT RECEIVED: \$4,250.00 USD\nTARGET: CHASE BANK (*9811)\nCOMPLETED STATUS: SUCCESS")
            put(COLUMN_TEMPLATE_ID, 0xFF635BFF.toInt())
        }
        db.insert(TABLE_SCREENSHOTS, null, seed1)

        val seed2 = ContentValues().apply {
            put(COLUMN_TITLE, "Slack Team Support Session")
            put(COLUMN_NOTES, "Customer bug reporting notes regarding layout overflow on foldables.")
            put(COLUMN_TAGS, "Chat, Work")
            put(COLUMN_TIMESTAMP, System.currentTimeMillis() - 18000000)
            put(COLUMN_IS_BOOKMARKED, 0)
            put(COLUMN_EXTRACTED_TEXT, "[10:24 AM] Alice: Hey Team, we have an overlap report in version 2.4\n[10:25 AM] Bob: I will add a 16KB alignment configuration check right away!")
            put(COLUMN_TEMPLATE_ID, 0xFF25D366.toInt())
        }
        db.insert(TABLE_SCREENSHOTS, null, seed2)
    }

    fun getAllScreenshots(): List<Screenshot> {
        val list = mutableListOf<Screenshot>()
        val db = readableDatabase
        val cursor = db.query(TABLE_SCREENSHOTS, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC")
        with(cursor) {
            while (moveToNext()) {
                list.add(
                    Screenshot(
                        id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
                        title = getString(getColumnIndexOrThrow(COLUMN_TITLE)),
                        notes = getString(getColumnIndexOrThrow(COLUMN_NOTES)),
                        tags = getString(getColumnIndexOrThrow(COLUMN_TAGS)),
                        timestamp = getLong(getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                        isBookmarked = getInt(getColumnIndexOrThrow(COLUMN_IS_BOOKMARKED)) == 1,
                        extractedText = getString(getColumnIndexOrThrow(COLUMN_EXTRACTED_TEXT)),
                        templateId = getInt(getColumnIndexOrThrow(COLUMN_TEMPLATE_ID))
                    )
                )
            }
            close()
        }
        return list
    }

    fun insertScreenshot(screenshot: Screenshot): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TITLE, screenshot.title.trim())
            put(COLUMN_NOTES, screenshot.notes.trim())
            put(COLUMN_TAGS, screenshot.tags.trim())
            put(COLUMN_TIMESTAMP, screenshot.timestamp)
            put(COLUMN_IS_BOOKMARKED, if (screenshot.isBookmarked) 1 else 0)
            put(COLUMN_EXTRACTED_TEXT, screenshot.extractedText.trim())
            put(COLUMN_TEMPLATE_ID, screenshot.templateId)
        }
        return db.insert(TABLE_SCREENSHOTS, null, values)
    }

    fun updateScreenshot(screenshot: Screenshot) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TITLE, screenshot.title.trim())
            put(COLUMN_NOTES, screenshot.notes.trim())
            put(COLUMN_TAGS, screenshot.tags.trim())
            put(COLUMN_IS_BOOKMARKED, if (screenshot.isBookmarked) 1 else 0)
            put(COLUMN_EXTRACTED_TEXT, screenshot.extractedText.trim())
        }
        db.update(TABLE_SCREENSHOTS, values, "$COLUMN_ID = ?", arrayOf(screenshot.id.toString()))
    }

    fun toggleBookmark(id: Long, isBookmarked: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IS_BOOKMARKED, if (isBookmarked) 1 else 0)
        }
        db.update(TABLE_SCREENSHOTS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun deleteScreenshot(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_SCREENSHOTS, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_SCREENSHOTS, null, null)
    }

    companion object {
        const val DATABASE_NAME = "screenshot_assistant.db"
        const val DATABASE_VERSION = 1

        const val TABLE_SCREENSHOTS = "screenshots"
        const val COLUMN_ID = "id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_NOTES = "notes"
        const val COLUMN_TAGS = "tags"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_IS_BOOKMARKED = "is_bookmarked"
        const val COLUMN_EXTRACTED_TEXT = "extracted_text"
        const val COLUMN_TEMPLATE_ID = "template_id"
    }
}

// ==========================================
// 3. ARCHITECTURAL MAIN ACTIVITY ENTRYPOINT
// ==========================================
class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: ScreenshotDatabaseHelper
    private val _screenshots = MutableStateFlow<List<Screenshot>>(emptyList())
    private val screenshots = _screenshots.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = ScreenshotDatabaseHelper(this)
        refreshScreenshots()

        setContent {
            ScreenshotAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("dashboard") }
                    val screenshotHistory by screenshots.collectAsState()
                    val context = LocalContext.current

                    ScreenshotAppContent(
                        currentScreen = currentScreen,
                        onScreenChange = { currentScreen = it },
                        screenshots = screenshotHistory,
                        onAddScreenshot = { title, notes, tags, ocrTxt, themeSeed ->
                            dbHelper.insertScreenshot(Screenshot(title = title, notes = notes, tags = tags, extractedText = ocrTxt, templateId = themeSeed))
                            refreshScreenshots()
                            Toast.makeText(context, "Captured & extracted into SQLite!", Toast.LENGTH_SHORT).show()
                        },
                        onUpdateScreenshot = { shot ->
                            dbHelper.updateScreenshot(shot)
                            refreshScreenshots()
                            Toast.makeText(context, "Metadata logs updated in SQLite", Toast.LENGTH_SHORT).show()
                        },
                        onToggleBookmark = { id, oldState ->
                            dbHelper.toggleBookmark(id, !oldState)
                            refreshScreenshots()
                        },
                        onDeleteScreenshot = { id ->
                            dbHelper.deleteScreenshot(id)
                            refreshScreenshots()
                            Toast.makeText(context, "Removed capture permanently", Toast.LENGTH_SHORT).show()
                        },
                        onClearAllData = {
                            dbHelper.clearAll()
                            refreshScreenshots()
                        }
                    )
                }
            }
        }
    }

    private fun refreshScreenshots() {
        _screenshots.value = dbHelper.getAllScreenshots()
    }
}

// ==========================================
// 4. JETPACK COMPOSE COMPOSABLE COMPONENT
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotAppContent(
    currentScreen: String,
    onScreenChange: (String) -> Unit,
    screenshots: List<Screenshot>,
    onAddScreenshot: (String, String, String, String, Int) -> Unit,
    onUpdateScreenshot: (Screenshot) -> Unit,
    onToggleBookmark: (Long, Boolean) -> Unit,
    onDeleteScreenshot: (Long) -> Unit,
    onClearAllData: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterTag by remember { mutableStateOf("All") }
    var selectedTab by remember { mutableStateOf(0) }

    var showCaptureDialog by remember { mutableStateOf(false) }
    var activeScreenshotDetail by remember { mutableStateOf<Screenshot?>(null) }

    val filteredScreenshots = screenshots.filter { shot ->
        val matchesSearch = shot.title.contains(searchQuery, ignoreCase = true) ||
                shot.extractedText.contains(searchQuery, ignoreCase = true) ||
                shot.notes.contains(searchQuery, ignoreCase = true)
        val matchesTag = selectedFilterTag == "All" || shot.tags.contains(selectedFilterTag, ignoreCase = true)
        val matchesTab = if (selectedTab == 1) shot.isBookmarked else true

        matchesSearch && matchesTag && matchesTab
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Camera,
                            contentDescription = "App Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = if (currentScreen == "settings") "Settings Panel" else "Screenshot OCR Assistant",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onScreenChange(if (currentScreen == "dashboard") "settings" else "dashboard") }) {
                        Icon(if (currentScreen == "dashboard") Icons.Default.Settings else Icons.Default.Home, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        floatingActionButton = {
            if (currentScreen == "dashboard") {
                FloatingActionButton(
                    onClick = { showCaptureDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CropFree, null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Mock Screen OCR", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == "dashboard" && selectedTab == 0,
                    onClick = { onScreenChange("dashboard"); selectedTab = 0 },
                    icon = { Icon(Icons.Default.GridOn, null) },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = currentScreen == "dashboard" && selectedTab == 1,
                    onClick = { onScreenChange("dashboard"); selectedTab = 1 },
                    icon = { Icon(Icons.Default.Star, null) },
                    label = { Text("Bookmarks") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)) {
            if (currentScreen == "settings") {
                ScreenshotSettingsView(
                    screenshots = screenshots,
                    onClearDatabase = onClearAllData,
                    onContactSupport = {
                        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("newmahod@gmail.com"))
                            putExtra(Intent.EXTRA_SUBJECT, "Screenshot Assistant Support")
                        }
                        try { context.startActivity(emailIntent) } catch (e: Exception) {
                            Toast.makeText(context, "Email client not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        placeholder = { Text("Search text inside screenshots...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val tags = listOf("All", "Finance", "Invoice", "Code", "Chat", "Work")
                        tags.forEach { tag ->
                            FilterChip(
                                selected = selectedFilterTag == tag,
                                onClick = { selectedFilterTag = tag },
                                label = { Text(tag) }
                            )
                        }
                    }

                    if (filteredScreenshots.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No Screenshots Found", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize().weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredScreenshots, key = { it.id }) { shot ->
                                ScreenshotGridItem(
                                    screenshot = shot,
                                    onItemClick = { activeScreenshotDetail = shot },
                                    onToggleBookmark = { onToggleBookmark(shot.id, shot.isBookmarked) }
                                )
                            }
                        }
                    }
                }
            }

            if (showCaptureDialog) {
                MockScreenCaptureDialog(
                    onDismiss = { showCaptureDialog = false },
                    onCaptureConfirmed = { title, notes, tags, text, seed ->
                        onAddScreenshot(title, notes, tags, text, seed)
                        showCaptureDialog = false
                    }
                )
            }

            activeScreenshotDetail?.let { currentShot ->
                ScreenshotDetailDialog(
                    screenshot = currentShot,
                    onDismiss = { activeScreenshotDetail = null },
                    onSaveEdits = { onUpdateScreenshot(it); activeScreenshotDetail = null },
                    onDelete = { onDeleteScreenshot(currentShot.id); activeScreenshotDetail = null }
                )
            }
        }
    }
}

// ==========================================
// 5. SCREENSHOT GRID CARD COMPOSABLE
// ==========================================
@Composable
fun ScreenshotGridItem(screenshot: Screenshot, onItemClick: () -> Unit, onToggleBookmark: () -> Unit) {
    val formatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val cardDate = formatter.format(Date(screenshot.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onItemClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(Color(screenshot.templateId).copy(alpha = 0.85f))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.align(Alignment.BottomStart)) {
                    Text(
                        text = "OCR ACTIVE",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = screenshot.extractedText.take(60) + "...",
                        fontSize = 9.sp, color = Color.White.copy(alpha = 0.8f),
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = if (screenshot.isBookmarked) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = null,
                        tint = if (screenshot.isBookmarked) Color(0xFFFFCC00) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(screenshot.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cardDate, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ==========================================
// 6. SCREENSHOT DETAIL VIEW
// ==========================================
@Composable
fun ScreenshotDetailDialog(screenshot: Screenshot, onDismiss: () -> Unit, onSaveEdits: (Screenshot) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var titleInput by remember { mutableStateOf(screenshot.title) }
    var noteInput by remember { mutableStateOf(screenshot.notes) }
    var tagsInput by remember { mutableStateOf(screenshot.tags) }
    var editableExtractedText by remember { mutableStateOf(screenshot.extractedText) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Screenshot OCR Data Card") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = titleInput, onValueChange = { titleInput = it }, label = { Text("Screenshot Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = tagsInput, onValueChange = { tagsInput = it }, label = { Text("Tags") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = noteInput, onValueChange = { noteInput = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                
                Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("EXTRACTED OCR TEXT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(editableExtractedText))
                                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                            }
                        }
                        OutlinedTextField(value = editableExtractedText, onValueChange = { editableExtractedText = it }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSaveEdits(screenshot.copy(title = titleInput, notes = noteInput, tags = tagsInput, extractedText = editableExtractedText)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))) { Text("Delete") } }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Metadata") },
            text = { Text("Are you sure you want to permanently erase this layout logs capture file?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Confirm Permanent Erase", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// 7. MOCK SCREEN SIMULATION SELECTOR
// ==========================================
@Composable
fun MockScreenCaptureDialog(onDismiss: () -> Unit, onCaptureConfirmed: (String, String, String, String, Int) -> Unit) {
    var selectedPresetIndex by remember { mutableStateOf(0) }
    val presets = listOf(
        MockScreenPreset("Starbucks Coffee", "Office fuel.", "Finance, Receipt", "STARBUCKS STORE #29401\n1x GRANDE LATTE - \$5.25\nTOTAL PAID: \$9.91", 0xFF006241.toInt()),
        MockScreenPreset("Stripe Software SaaS Bill", "Cloud billing.", "Finance, SaaS", "STRIPE INVOICE #INV002\nAMOUNT DUE: \$105.80 USD\nSTATUS: SUCCESSFUL", 0xFF635BFF.toInt()),
        MockScreenPreset("Github Repository Error", "Android compile break.", "Code, Dev", "Error: Process exited with code 1.\nCannot find name 'RoomDatabaseHelper'.", 0xFF24292F.toInt())
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Simulated Device Screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                presets.forEachIndexed { idx, preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selectedPresetIndex = idx }.border(2.dp, if (selectedPresetIndex == idx) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color(preset.colorSeed)))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(preset.title, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { val p = presets[selectedPresetIndex]; onCaptureConfirmed(p.title, p.notes, p.tags, p.extractedText, p.colorSeed) }) { Text("Capture & Run OCR") } }
    )
}

data class MockScreenPreset(val title: String, val notes: String, val tags: String, val extractedText: String, val colorSeed: Int)

// ==========================================
// 8. SETTINGS VIEW
// ==========================================
@Composable
fun ScreenshotSettingsView(screenshots: List<Screenshot>, onClearDatabase: () -> Unit, onContactSupport: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Database & OCR Statistics", fontWeight = FontWeight.Bold)
                Text("Total Captured Items: ${screenshots.size}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onContactSupport, modifier = Modifier.fillMaxWidth()) { Text("Contact Store Support") }
        Button(onClick = { showDeleteConfirm = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), modifier = Modifier.fillMaxWidth()) { Text("Clear SQLite Library") }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Erase Entire Database") },
            text = { Text("This will permanently drop your library tables and wipe out all local data logs. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onClearDatabase()
                    }
                ) {
                    Text("Confirm Permanent Erase", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// 9. THEME COMPILER
// ==========================================
@Composable
fun ScreenshotAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFD0BCFF),
            background = Color(0xFF141318),
            surface = Color(0xFF141318),
            surfaceVariant = Color(0xFF49454F)
        ),
        content = content
    )
}
