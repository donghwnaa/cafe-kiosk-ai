
package com.example.mallo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mallo.ui.theme.MALLOTheme
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MALLOTheme {
                MalloApp()
            }
        }
    }
}

@Composable
fun MalloApp() {

    var currentScreen by remember {
        mutableStateOf("start")
    }

    when (currentScreen) {

        "start" -> StartScreen(
            onStartClick = {
                currentScreen = "voice"
            }
        )

        "voice" -> VoiceOrderScreen(
            onBackClick = {
                currentScreen = "start"
            }
        )
    }
}

@Composable
fun StartScreen(onStartClick: () -> Unit) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),

        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.height(180.dp))

        Image(
            painter = painterResource(
                id = R.drawable.coffee_cup
            ),
            contentDescription = "커피잔 이미지",
            modifier = Modifier
                .size(150.dp),
            contentScale = ContentScale.Fit
        )


        // 메인 제목
        Text(
            text = "음성 주문 키오스크",
            fontSize = 27.sp,
            fontFamily = FontFamily(
                Font(R.font.nanum_myeongjo_extra_bold)
            ),
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(1.dp))

        // 부제목
        Text(
            text = "말로 주문하는 쉬운 키오스크",
            fontSize = 13.sp,
            fontFamily = FontFamily(
                Font(R.font.nanum_myeongjo)
            ),
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(65.dp))

        // 자주색 음성 주문 버튼

        Button(
            onClick = onStartClick,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(150.dp),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(
                horizontal = 3.dp,
                vertical = 12.dp
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF943E9C)
            )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {

                Image(
                    painter = painterResource(
                        id = R.drawable.voice_order_icon
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(100.dp)
                )


                Text(
                    text = "음성으로\n주문하기",
                    fontSize = 23.sp,
                    lineHeight = 31.sp,
                    fontFamily = FontFamily(
                        Font(R.font.nanum_myeongjo_bold)
                    ),
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(150.dp))

        // 하단 안내 문구
        Text(
            text = "버튼을 누르고 말씀해주세요.",

            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    Color(0xFFF4F4F4),
                    RoundedCornerShape(12.dp)
                )
                .padding(5.dp),

            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            fontFamily = FontFamily(
                Font(R.font.nanum_myeongjo_bold)
            ),
            color = Color.DarkGray
        )
    }
}

@Composable
fun VoiceOrderScreen(onBackClick: () -> Unit) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),

        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "음성 주문 화면",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "무엇을 주문하시겠어요?",
            fontSize = 22.sp,
            color = Color.DarkGray
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onBackClick
        ) {
            Text("처음으로 돌아가기")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StartScreenPreview() {
    MALLOTheme {
        StartScreen(onStartClick = {})
    }
}
