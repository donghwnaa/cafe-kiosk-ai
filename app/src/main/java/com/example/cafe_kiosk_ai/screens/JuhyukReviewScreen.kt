package com.example.cafe_kiosk_ai.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cafe_kiosk_ai.R

/** 주문 확인 UI 원본. 데이터와 음성 처리는 정적 예시 값으로만 표시합니다. */
@Composable
fun JuhyukReviewScreen(onConfirm: () -> Unit, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("☕", fontSize = 32.sp)
            Text("음성 주문 키오스크", Modifier.weight(1f).padding(start = 8.dp), fontSize = 21.sp, fontFamily = FontFamily.Serif)
            TextButton(onClick = onHome) { Text("🏠", fontSize = 28.sp) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF5F1F1), RoundedCornerShape(24.dp)).padding(12.dp)) {
            Text("🤖   주문하신 상품이 맞나요?", Modifier.fillMaxWidth().background(Color(0xFFF8E8E8), RoundedCornerShape(20.dp)).padding(12.dp), fontSize = 19.sp, fontFamily = FontFamily.Serif)
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).background(Color(0xFFF8E8E8), RoundedCornerShape(20.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.americano),
                    contentDescription = "아메리카노 이미지",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(170.dp).background(Color.White),
                )
                Text("아메리카노", Modifier.padding(top = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("온도 : ICE(차가움)\n크기 : R(보통 크기)\n수량 : 2", Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 18.sp, lineHeight = 25.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(118.dp).background(Color(0xFF706CF4), CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗣️🎙️", fontSize = 34.sp)
                Text("말하기", fontSize = 21.sp, color = Color.White, fontFamily = FontFamily.Serif)
            }
        }
        Text("●  듣는 중...", Modifier.padding(6.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onConfirm, Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF706CF4))) {
            Text("“맞아요”", fontSize = 24.sp, fontFamily = FontFamily.Serif)
        }
    }
}
