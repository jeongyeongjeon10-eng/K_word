
package com.example.kword

import android.content.Context
import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "kword_data"
private const val KEY_API = "api_key"
private const val KEY_WORDS = "saved_words"

data class Sense(
    val definition: String,
    val chinese: String
)

data class Example(
    val sentence: String
)

data class DictionaryWord(
    val code: String,
    val word: String,
    val pos: String,
    val senses: List<Sense>,
    val examples: List<Example> = emptyList()
)

data class SavedWord(
    val id: String,
    val word: String,
    val pos: String,
    val definitions: String,
    val examples: List<String>,
    val addedAt: Long,
    val stage: Int,
    val nextReviewAt: Long,
    val status: String,
    val reviewCount: Int
)

private fun dateText(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))

private fun loadWords(context: Context): List<SavedWord> {
    val raw = context.getSharedPreferences(PREFS, 0)
        .getString(KEY_WORDS, "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val ex = o.getJSONArray("examples")
            SavedWord(
                id = o.getString("id"),
                word = o.getString("word"),
                pos = o.getString("pos"),
                definitions = o.getString("definitions"),
                examples = (0 until ex.length()).map { ex.getString(it) },
                addedAt = o.getLong("addedAt"),
                stage = o.getInt("stage"),
                nextReviewAt = o.getLong("nextReviewAt"),
                status = o.getString("status"),
                reviewCount = o.getInt("reviewCount")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveWords(context: Context, words: List<SavedWord>) {
    val array = JSONArray()
    words.forEach { w ->
        val ex = JSONArray()
        w.examples.forEach { ex.put(it) }
        array.put(
            JSONObject().apply {
                put("id", w.id)
                put("word", w.word)
                put("pos", w.pos)
                put("definitions", w.definitions)
                put("examples", ex)
                put("addedAt", w.addedAt)
                put("stage", w.stage)
                put("nextReviewAt", w.nextReviewAt)
                put("status", w.status)
                put("reviewCount", w.reviewCount)
            }
        )
    }
    context.getSharedPreferences(PREFS, 0)
        .edit().putString(KEY_WORDS, array.toString()).apply()
}

private fun requestXml(urlText: String): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15000
    connection.readTimeout = 20000
    return try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun parseWords(xml: String): List<DictionaryWord> {
    val parser = Xml.newPullParser()
    parser.setInput(xml.reader())

    val result = mutableListOf<DictionaryWord>()
    var code = ""
    var word = ""
    var pos = ""
    var definition = ""
    var chinese = ""
    var senses = mutableListOf<Sense>()
    var inItem = false
    var inSense = false

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "item" -> {
                        inItem = true
                        code = ""
                        word = ""
                        pos = ""
                        senses = mutableListOf()
                    }
                    "sense" -> {
                        inSense = true
                        definition = ""
                        chinese = ""
                    }
                }
            }
            XmlPullParser.END_TAG -> {
                when (parser.name) {
                    "sense" -> {
                        if (definition.isNotBlank()) {
                            senses.add(Sense(definition, chinese))
                        }
                        inSense = false
                    }
                    "item" -> {
                        if (word.isNotBlank()) {
                            result.add(
                                DictionaryWord(
                                    code, word, pos, senses.toList()
                                )
                            )
                        }
                        inItem = false
                    }
                }
            }
            XmlPullParser.TEXT -> {
                val value = parser.text?.trim().orEmpty()
                when (parser.name) {
                    "target_code" -> if (inItem) code = value
                    "word" -> if (inItem && !inSense) word = value
                    "pos" -> if (inItem) pos = value
                    "definition" -> if (inSense) definition = value
                    "trans_dfn" -> if (inSense) chinese = value
                    "trans_word" -> if (inSense && chinese.isBlank()) chinese = value
                }
            }
        }
        event = parser.next()
    }
    return result
}

private fun parseExamples(xml: String, targetCode: String): List<Example> {
    val parser = Xml.newPullParser()
    parser.setInput(xml.reader())
    val result = mutableListOf<Example>()
    var code = ""
    var sentence = ""
    var inItem = false

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "item") {
                    inItem = true
                    code = ""
                    sentence = ""
                }
            }
            XmlPullParser.END_TAG -> {
                if (parser.name == "item") {
                    if (code == targetCode && sentence.isNotBlank()) {
                        result.add(Example(sentence))
                    }
                    inItem = false
                }
            }
            XmlPullParser.TEXT -> {
                val value = parser.text?.trim().orEmpty()
                when (parser.name) {
                    "target_code" -> if (inItem) code = value
                    "example" -> if (inItem) sentence = value
                }
            }
        }
        event = parser.next()
    }
    return result.distinctBy { it.sentence }.take(10)
}

private suspend fun searchDictionary(
    apiKey: String,
    query: String
): List<DictionaryWord> = withContext(Dispatchers.IO) {
    val key = URLEncoder.encode(apiKey, "UTF-8")
    val q = URLEncoder.encode(query, "UTF-8")
    val base = "https://krdict.korean.go.kr/api/search?key=$key&q=$q&num=30&translated=y&trans_lang=11"
    val words = parseWords(requestXml(base))

    words.map { item ->
        val exampleUrl =
            "https://krdict.korean.go.kr/api/search?key=$key&q=$q&num=100&part=exam"
        val examples = try {
            parseExamples(requestXml(exampleUrl), item.code)
        } catch (_: Exception) {
            emptyList()
        }
        item.copy(examples = examples)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KWordApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KWordApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }

    var apiKey by remember { mutableStateOf(prefs.getString(KEY_API, "") ?: "") }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
    var keyInput by remember { mutableStateOf(apiKey) }

    var words by remember { mutableStateOf(loadWords(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DictionaryWord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var reviewIndex by remember { mutableIntStateOf(0) }
    var answerShown by remember { mutableStateOf(false) }

    fun updateWords(newWords: List<SavedWord>) {
        words = newWords
        saveWords(context, newWords)
    }

    fun saveDictionaryWord(item: DictionaryWord, selected: List<String>) {
        val definitionText = item.senses.joinToString("\n\n") {
            buildString {
                append(it.definition)
                if (it.chinese.isNotBlank()) append("\n中文：${it.chinese}")
            }
        }
        val now = System.currentTimeMillis()
        val saved = SavedWord(
            id = item.code.ifBlank { item.word },
            word = item.word,
            pos = item.pos,
            definitions = definitionText,
            examples = selected,
            addedAt = now,
            stage = 0,
            nextReviewAt = now + 24L * 60 * 60 * 1000,
            status = "待学习",
            reviewCount = 0
        )
        updateWords(
            listOf(saved) + words.filterNot { it.id == saved.id }
        )
        message = "已加入生词本：${item.word}"
    }

    val dueWords = words.filter {
        it.status != "已掌握" && it.nextReviewAt <= System.currentTimeMillis()
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("K-Word 韩语学习") },
                    actions = {
                        IconButton(onClick = {
                            keyInput = apiKey
                            showSettings = true
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    val labels = listOf("查词", "生词本", "今日复习", "统计")
                    val icons = listOf(
                        Icons.Default.Search,
                        Icons.Default.MenuBook,
                        Icons.Default.Today,
                        Icons.Default.BarChart
                    )
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (message.isNotBlank()) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                when (tab) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("韩语查词", style = MaterialTheme.typography.headlineSmall)
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("输入韩语单词") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    if (apiKey.isBlank()) {
                                        showSettings = true
                                    } else if (query.isNotBlank()) {
                                        scope.launch {
                                            loading = true
                                            message = ""
                                            try {
                                                results = searchDictionary(apiKey, query.trim())
                                                if (results.isEmpty()) message = "没有查到结果，请换个关键词试试。"
                                            } catch (e: Exception) {
                                                message = "查询失败：${e.message ?: "请检查网络和 API Key"}"
                                            }
                                            loading = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (loading) "查询中…" else "查询")
                            }

                            results.forEach { item ->
                                var selected by remember(item.code) {
                                    mutableStateOf(setOf<Int>())
                                }
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(item.word, style = MaterialTheme.typography.titleLarge)
                                        Text("词性：${item.pos}")
                                        item.senses.forEachIndexed { index, sense ->
                                            Text("${index + 1}. ${sense.definition}")
                                            if (sense.chinese.isNotBlank()) {
                                                Text(
                                                    "中文：${sense.chinese}",
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        if (item.examples.isNotEmpty()) {
                                            Divider()
                                            Text("选择要保存的例句")
                                            item.examples.forEachIndexed { index, example ->
                                                Row {
                                                    Checkbox(
                                                        checked = index in selected,
                                                        onCheckedChange = { checked ->
                                                            selected = if (checked) {
                                                                selected + index
                                                            } else {
                                                                selected - index
                                                            }
                                                        }
                                                    )
                                                    Text(
                                                        example.sentence,
                                                        modifier = Modifier.padding(top = 12.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            Text("暂时没有找到例句")
                                        }
                                        Button(
                                            onClick = saveDictionaryWord(
                                                item,
                                                selected.mapNotNull {
                                                    item.examples.getOrNull(it)?.sentence
                                                }
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("加入生词本")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        var filter by remember { mutableStateOf("全部") }
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Text("我的生词本", style = MaterialTheme.typography.headlineSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("全部", "待学习", "复习中", "已掌握").forEach {
                                    FilterChip(
                                        selected = filter == it,
                                        onClick = { filter = it },
                                        label = { Text(it) }
                                    )
                                }
                            }
                            val filtered = words.filter {
                                filter == "全部" || it.status == filter
                            }.sortedByDescending { it.addedAt }
                            if (filtered.isEmpty()) {
                                Text("这里还没有单词，先去查词并添加吧。")
                            } else {
                                LazyColumn {
                                    val groups = filtered.groupBy { dateText(it.addedAt) }
                                    groups.forEach { (date, group) ->
                                        item {
                                            Text(
                                                "$date · ${group.size} 个单词",
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.padding(vertical = 10.dp)
                                            )
                                        }
                                        items(group, key = { it.id }) { w ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 8.dp)
                                            ) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text(w.word, style = MaterialTheme.typography.titleLarge)
                                                    Text("${w.pos} · ${w.status}")
                                                    Text(w.definitions)
                                                    w.examples.forEach {
                                                        Text("例句：$it")
                                                    }
                                                    Text("下次复习：${dateText(w.nextReviewAt)}")
                                                    TextButton(onClick = {
                                                        updateWords(words.filterNot { it.id == w.id })
                                                    }) {
                                                        Text("删除")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("今日复习", style = MaterialTheme.typography.headlineSmall)
                            Text("今天需要复习：${dueWords.size} 个单词")
                            if (dueWords.isEmpty()) {
                                Text("今天没有到期的复习任务，做得不错！")
                            } else {
                                val current = dueWords.getOrNull(
                                    reviewIndex.coerceIn(0, dueWords.lastIndex)
                                )
                                if (current != null) {
                                    Text(
                                        "${reviewIndex + 1} / ${dueWords.size}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(
                                            Modifier.padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                current.word,
                                                style = MaterialTheme.typography.headlineMedium
                                            )
                                            if (answerShown) {
                                                Text(current.pos)
                                                Text(current.definitions)
                                                current.examples.forEach { Text("例句：$it") }
                                            } else {
                                                Text("先回忆一下这个单词的意思。")
                                            }
                                            Button(
                                                onClick = { answerShown = true },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("显示答案")
                                            }
                                            if (answerShown) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(
                                                        onClick = {
                                                            val updated = current.copy(
                                                                stage = 0,
                                                                status = "复习中",
                                                                nextReviewAt = System.currentTimeMillis() + 24L * 60 * 60 * 1000,
                                                                reviewCount = current.reviewCount + 1
                                                            )
                                                            updateWords(words.map {
                                                                if (it.id == current.id) updated else it
                                                            })
                                                            answerShown = false
                                                            reviewIndex = 0
                                                        },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("没记住") }
                                                    Button(
                                                        onClick = {
                                                            val newStage = current.stage + 1
                                                            val intervals = listOf(1, 3, 7, 14, 30)
                                                            val mastered = newStage >= 6
                                                            val days = intervals[
                                                                (newStage - 1).coerceIn(0, 4)
                                                            ]
                                                            val updated = current.copy(
                                                                stage = newStage,
                                                                status = if (mastered) "已掌握" else "复习中",
                                                                nextReviewAt = if (mastered) Long.MAX_VALUE else
                                                                    System.currentTimeMillis() + days * 24L * 60 * 60 * 1000,
                                                                reviewCount = current.reviewCount + 1
                                                            )
                                                            updateWords(words.map {
                                                                if (it.id == current.id) updated else it
                                                            })
                                                            answerShown = false
                                                            reviewIndex = 0
                                                        },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("记住了") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        val total = words.size
                        val mastered = words.count { it.status == "已掌握" }
                        val rate = if (total == 0) 0 else mastered * 100 / total
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("学习统计", style = MaterialTheme.typography.headlineSmall)
                            StatCard("生词总数", "$total")
                            StatCard("今日待复习", "${dueWords.size}")
                            StatCard("已掌握", "$mastered")
                            StatCard("掌握率", "$rate%")
                            Text("继续坚持积累，每天复习一点点。")
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("API 设置") },
            text = {
                Column {
                    Text("请输入韩国基础词典 API Key。密钥只保存在本机 App 私有设置中。")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = keyInput.trim()
                    prefs.edit().putString(KEY_API, apiKey).apply()
                    showSettings = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(18.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
