package com.jacobp.szklarnia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jacobp.szklarnia.ui.theme.SzklarniaTheme
import com.jacobp.szklarnia.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SzklarniaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen()
                }
            }
        }
    }
}

@Composable
fun DashboardScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Górny pasek statusu połączenia
        Surface(
            color = GreenActive,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("POŁĄCZONO ZE SZKLARNIĄ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Temperatura i Wilgotność - Kafelki
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricCard("Temperatura", "25.0°C", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(16.dp))
            MetricCard("Wilgotność", "60%", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Gleba
        MetricCard("Stan Gleby", "Mokro (65%)", Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(32.dp))

        // Przełączniki dużego kalibru
        ControlButton("WENTYLATOR", true)
        Spacer(modifier = Modifier.height(16.dp))
        ControlButton("PODLEWANIE", false)

        Spacer(modifier = Modifier.weight(1f))

        // Przycisk Aktualizacji
        Button(
            onClick = { /* TODO */ },
            colors = ButtonDefaults.buttonColors(containerColor = OrangeWarning),
            modifier = Modifier.fillMaxWidth().height(80.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("DOSTĘPNA AKTUALIZACJA", color = CharcoalText, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(140.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 18.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontSize = 36.sp, color = CharcoalText, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun ControlButton(label: String, isOn: Boolean) {
    val bgColor = if (isOn) GreenActive else UnselectedGray
    val textColor = if (isOn) Color.White else CharcoalText

    Button(
        onClick = { /* TODO */ },
        colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(if (isOn) "$label: WŁĄCZONY" else "$label: WYŁĄCZONY", color = textColor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}
