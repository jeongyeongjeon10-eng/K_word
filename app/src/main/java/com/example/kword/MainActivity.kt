
package com.example.kword

import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Green = Color(0xFF287A61)
private val Background = Color(0xFFF6F8F5)

data class Word(
    val name: String,
    val pos: String,
    val definition: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var apiKey by remember { mutableStateOf("") }
            var query by remember { mutableStateOf("") }
            var results by remember { mutableStateOf(listOf<Word>()) }
            var message by remember { mutableStateOf("오늘의 한국어 단어를 찾아보세요.") }
            var loading by remember { mutableStateOf(false) }

            val scope = rememberCoroutineScope()

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    background = Background
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background)
                        .statusBarsPadding()
                        .padding(20.dp)
                ) {
                    Text(
                        "K-WORD",
                        color = Green,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "한국어 단어장",
                        fontSize = 29.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        "你的韩语随身词典",
                        color = Color.Gray
                    )

                    Spacer(Modifier.height(28.dp))

                    Text(
                        "今天想查什么？",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("输入韩语，例如 냉랭하다") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (apiKey.isBlank() || query.isBlank()) {
                                message = "请填写 API Key 和查询单词"
                            } else {
                                loading = true
                                message = "正在查询..."

                                scope.launch {
                                    try {
                                        results = withContext(Dispatchers.IO) {
                                            searchWords(apiKey, query)
                                        }
                                        message = "找到 ${results.size} 条结果"
                                    } catch (e: Exception) {
                                        message = "查询失败：${e.message}"
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("搜索单词")
                    }

                    Spacer(Modifier.height(18.dp))

                    Text(
                        message,
                        color = Green,
                        fontSize = 14.sp
                    )

                    if (loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(results) { word ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    Text(
                                        word.name,
                                        fontSize = 23.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        word.pos,
                                        color = Green,
                                        fontSize = 13.sp
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        word.definition,
                                        fontSize = 15.sp,
                                        lineHeight = 23.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        "K-WORD · Learn Korean every day",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

private fun searchWords(key: String, word: String): List<Word> {
    val params =
        "key=${URLEncoder.encode(key, "UTF-8")}" +
        "&q=${URLEncoder.encode(word, "UTF-8")}" +
        "&num=20"

    val connection = URL(
        "https://krdict.korean.go.kr/api/search?$params"
    ).openConnection() as HttpURLConnection

    connection.connectTimeout = 15000
    connection.readTimeout = 15000

    try {
        if (connection.responseCode !in 200..299) {
            throw Exception("HTTP ${connection.responseCode}")
        }

        val parser = Xml.newPullParser()
        parser.setInput(connection.inputStream, "UTF-8")

        val words = mutableListOf<Word>()

        var currentTag = ""
        var name = ""
        var pos = ""
        var definition = ""
        var inItem = false

        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name

                    if (currentTag == "item") {
                        inItem = true
                        name = ""
                        pos = ""
                        definition = ""
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inItem) {
                        val value = parser.text.trim()

                        when (currentTag) {
                            "word" -> if (value.isNotEmpty()) name = value
                            "pos" -> if (value.isNotEmpty()) pos = value
                            "definition" -> {
                                if (value.isNotEmpty()) {
                                    definition += value
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && inItem) {
                        if (name.isNotEmpty()) {
                            words.add(
                                Word(name, pos, definition)
                            )
                        }
                        inItem = false
                    }
                    currentTag = ""
                }
            }

            event = parser.next()
        }

        return words.distinctBy { it.name + it.pos + it.definition }

    } finally {
        connection.disconnect()
    }
}
