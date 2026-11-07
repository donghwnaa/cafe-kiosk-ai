package com.example.cafe_kiosk_ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cafe_kiosk_ai.order.KioskStage
import com.example.cafe_kiosk_ai.order.KioskViewModel
import com.example.cafe_kiosk_ai.order.VoiceStage

private val Accent = Color(0xFF706CF4)
private val SoftPink = Color(0xFFF8E8E8)
private val Ink = Color(0xFF151515)

class MainActivity : ComponentActivity() {
    private val viewModel: KioskViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Color.White)) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                KioskScreen(
                    state = state,
                    onConfirm = viewModel::confirmOrder,
                    onHome = viewModel::resetHome,
                    onBack = viewModel::returnToProductReview,
                )
            }
        }
    }
}

@Composable
private fun KioskScreen(
    state: com.example.cafe_kiosk_ai.order.KioskUiState,
    onConfirm: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("☕", fontSize = 34.sp)
            Text("음성 주문 키오스크", Modifier.weight(1f).padding(start = 8.dp), fontSize = 21.sp, fontFamily = FontFamily.Serif, color = Ink)
            TextButton(onClick = onHome) { Text("🏠", fontSize = 28.sp) }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F1F1)), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RobotBadge()
                    Text(if (state.stage == KioskStage.PRODUCT_REVIEW) "주문하신 상품이 맞나요?" else "주문 내역을 확인해 주세요.", Modifier.weight(1f).background(SoftPink, RoundedCornerShape(20.dp)).padding(10.dp), fontSize = 19.sp, fontFamily = FontFamily.Serif, color = Ink)
                }
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RobotBadge()
                    Column(Modifier.weight(1f).background(SoftPink, RoundedCornerShape(22.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🥤", Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(4.dp)).padding(vertical = 8.dp), fontSize = 52.sp, textAlign = TextAlign.Center)
                        if (state.lines.isEmpty()) {
                            Text("아메리카노", Modifier.padding(top = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("온도 : ICE(차가움)\n크기 : R(보통 크기)\n수량 : 2", Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 18.sp, lineHeight = 25.sp)
                        } else {
                            state.lines.forEach { line ->
                                Text(line.menu.name, Modifier.padding(top = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("온도 : ${if (line.options.temperature == "ICE") "ICE(차가움)" else "HOT(따뜻함)"}\n크기 : ${line.options.size}\n수량 : ${line.quantity}", Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 18.sp, lineHeight = 25.sp)
                            }
                            if (state.stage == KioskStage.ORDER_REVIEW) Text("총 금액 : ${"%,d".format(state.total)}원", Modifier.padding(top = 10.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(150.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.voice == VoiceStage.PROCESSING) "⏳" else "🗣️🎙️", fontSize = 40.sp)
                Text("말하기", fontSize = 25.sp, fontFamily = FontFamily.Serif, color = Color.White)
            }
        }
        Row(Modifier.padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (state.voice == VoiceStage.IDLE) Color(0xFF00BF35) else Color(0xFFFFA000), CircleShape))
            Text(state.message ?: if (state.voice == VoiceStage.PROCESSING) "주문을 확인하고 있어요..." else "예시 주문 데이터", Modifier.padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.transcript != null) Text("인식 문장  ·  ${state.transcript}", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 14.sp, textAlign = TextAlign.Center, color = Color.DarkGray)
        Button(
            onClick = if (state.stage == KioskStage.PRODUCT_REVIEW) onConfirm else onBack,
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(bottom = 8.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text(if (state.stage == KioskStage.PRODUCT_REVIEW) "“맞아요”" else "돌아가기", fontSize = 27.sp, fontFamily = FontFamily.Serif) }
    }
}

@Composable
private fun RobotBadge() {
    Box(Modifier.size(48.dp).background(Color(0xFFE9EDF3), CircleShape), contentAlignment = Alignment.Center) {
        Text("🤖", fontSize = 28.sp)
    }
}
