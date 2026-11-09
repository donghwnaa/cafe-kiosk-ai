package com.example.cafe_kiosk_ai.ui.page4

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cafe_kiosk_ai.R

/*
 * 4페이지: 주문 내용 확인 화면
 *
 * - 장바구니 목록, 총 결제 금액 안내(AI 말풍선), 말하기 버튼, "계산할게요" 버튼으로 구성
 * - 화면 상태는 모두 파라미터로 받기 때문에, 나중에 ViewModel이 들고 있는
 *   장바구니/대화/음성 상태를 Page4CartItem, Page4ChatMessage, Page4VoiceState로 바꿔서 넘기면 됨
 */

// ---------- 화면에 넘겨줄 데이터 ----------

data class Page4CartItem(
    val name: String,
    val temperature: String, // "ICE" 또는 "HOT"
    val unitPrice: Int,
    val quantity: Int,
    @DrawableRes val imageRes: Int,
    val size: String? = null, // 주문 데이터의 크기 코드 "R"(보통) / "L"(큰). 아직 안 정해졌으면 null
) {
    val lineTotal: Int get() = unitPrice * quantity
}

/** fromUser = true 이면 오른쪽(손님) 말풍선, false 이면 왼쪽(AI) 말풍선. highlight 글자는 빨간색으로 강조 */
data class Page4ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val highlight: String? = null,
)

enum class Page4VoiceState { IDLE, LISTENING, PROCESSING }

// ---------- 디자인 값 ----------

private object Page4Colors {
    val Ink = Color(0xFF000000)
    val OptionText = Color(0xFFB8A0A0)
    val CardBorder = Color(0xFF000000)
    val ChatOuter = Color(0xFFF6F2F2)
    val AiBubble = Color(0xFFF8E8E8)
    val UserBubble = Color(0xFF9AC6FF)
    val Highlight = Color(0xFFFF0C0C)
    val MicCircle = Color(0xFF6C6CEF)
    val Listening = Color(0xFF00BA22)
    val Processing = Color(0xFFFFA000)
    val Idle = Color(0xFF9E9E9E)
    val PayButton = Color(0xFF7373F9)
}

private val Serif = FontFamily.Serif
private val CardShape = RoundedCornerShape(10.dp)

fun formatWon(price: Int): String = "%,d원".format(price)

/** 크기 코드 */
fun sizeLabel(size: String?): String? = when (size?.uppercase()) {
    null, "" -> null
    "R", "REGULAR" -> "보통 크기"
    "L", "LARGE" -> "큰 크기"
    else -> size
}

/** AI 안내 문구 기본값: "총 결제 금액 : 8,000원입니다. 확인부탁드려요" */
fun page4TotalMessage(total: Int): Page4ChatMessage {
    val amount = "%,d".format(total)
    return Page4ChatMessage(
        text = "총 결제 금액 : ${amount}원입니다.\n확인부탁드려요",
        fromUser = false,
        highlight = amount,
    )
}

// ---------- 화면 ----------

@Composable
fun OrderConfirmScreen(
    items: List<Page4CartItem>,
    messages: List<Page4ChatMessage>,
    voiceState: Page4VoiceState,
    onMicClick: () -> Unit,
    onPayClick: () -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Page4Header(onHomeClick = onHomeClick)

        Text(
            text = "주문 내용을 확인해주세요",
            modifier = Modifier.padding(top = 10.dp),
            fontFamily = Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = Page4Colors.Ink,
        )

        CartCard(
            cartItems = items,
            modifier = Modifier
                .padding(start = 27.dp, end = 27.dp, top = 20.dp)
                .fillMaxWidth(),
        )

        ChatHistory(
            messages = messages,
            modifier = Modifier
                .padding(start = 23.dp, end = 23.dp, top = 24.dp)
                .fillMaxWidth()
                .height(141.dp),
        )

        Spacer(Modifier.weight(1f))

        MicButton(voiceState = voiceState, onClick = onMicClick)

        VoiceStatus(
            voiceState = voiceState,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onPayClick,
            modifier = Modifier
                .padding(horizontal = 23.dp)
                .fillMaxWidth()
                .height(77.dp),
            shape = CardShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Page4Colors.PayButton,
                contentColor = Color.White,
            ),
        ) {
            Text(text = "“계산할게요”", fontFamily = Serif, fontSize = 30.sp)
        }
    }
}

@Composable
private fun Page4Header(onHomeClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.page4_logo_cup),
            contentDescription = null,
            modifier = Modifier.size(69.dp),
        )
        Text(
            text = "음성 주문 키오스크",
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            fontFamily = Serif,
            fontSize = 15.sp,
            color = Page4Colors.Ink,
        )
        Image(
            painter = painterResource(R.drawable.page4_home),
            contentDescription = "처음으로",
            modifier = Modifier
                .size(61.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onHomeClick),
        )
    }
}

@Composable
private fun CartCard(cartItems: List<Page4CartItem>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 107.dp)
            .border(BorderStroke(1.dp, Page4Colors.CardBorder), CardShape)
            .clip(CardShape),
    ) {
        if (cartItems.isEmpty()) {
            Text(
                text = "담긴 메뉴가 없어요",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                fontFamily = Serif,
                fontSize = 19.sp,
                color = Page4Colors.Idle,
            )
        } else {
            // 메뉴가 2개 이상이면 카드 안에서 스크롤 (1.5칸 높이까지만 보여서 아래 버튼이 밀려나지 않음)
            LazyColumn(modifier = Modifier.heightIn(max = 130.dp)) {
                items(cartItems) { item ->
                    CartRow(item)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        thickness = 1.dp,
                        color = Color(0xFF7F7F7F),
                    )
                }
            }
        }
    }
}

@Composable
private fun CartRow(item: Page4CartItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(item.imageRes),
            contentDescription = item.name,
            modifier = Modifier.size(71.dp),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = item.name, fontFamily = Serif, fontSize = 19.sp, color = Page4Colors.Ink)
            Text(
                text = listOfNotNull(item.temperature, sizeLabel(item.size)).joinToString(" · "),
                fontFamily = Serif,
                fontSize = 19.sp,
                color = Page4Colors.OptionText,
            )
            Row {
                Text(text = formatWon(item.unitPrice), fontFamily = Serif, fontSize = 19.sp, color = Page4Colors.Ink)
                Text(
                    text = "x${item.quantity}",
                    modifier = Modifier.padding(start = 22.dp),
                    fontFamily = Serif,
                    fontSize = 19.sp,
                    color = Page4Colors.Ink,
                )
            }
        }
        Text(
            text = formatWon(item.lineTotal),
            fontFamily = Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            color = Page4Colors.Ink,
        )
    }
}

@Composable
private fun ChatHistory(messages: List<Page4ChatMessage>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Page4Colors.ChatOuter, CardShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White, CardShape)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                if (message.fromUser) UserBubble(message) else AiBubble(message)
            }
        }
    }
}

@Composable
private fun AiBubble(message: Page4ChatMessage) {
    Row(verticalAlignment = Alignment.Top) {
        Image(
            painter = painterResource(R.drawable.page4_robot),
            contentDescription = "AI",
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = highlighted(message),
            modifier = Modifier
                .padding(start = 6.dp)
                .widthIn(max = 254.dp)
                .background(Page4Colors.AiBubble, CardShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            fontFamily = Serif,
            fontSize = 17.sp,
            lineHeight = 20.sp,
            color = Page4Colors.Ink,
        )
    }
}

@Composable
private fun UserBubble(message: Page4ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = highlighted(message),
            modifier = Modifier
                .widthIn(min = 136.dp, max = 220.dp)
                .background(Page4Colors.UserBubble, CardShape)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            fontFamily = Serif,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            color = Page4Colors.Ink,
        )
        Image(
            painter = painterResource(R.drawable.page4_user),
            contentDescription = "손님",
            modifier = Modifier
                .padding(start = 6.dp)
                .size(25.dp),
        )
    }
}

private fun highlighted(message: Page4ChatMessage) = buildAnnotatedString {
    val key = message.highlight
    val start = if (key.isNullOrEmpty()) -1 else message.text.indexOf(key)
    if (start < 0) {
        append(message.text)
    } else {
        append(message.text.substring(0, start))
        withStyle(SpanStyle(color = Page4Colors.Highlight)) { append(key!!) }
        append(message.text.substring(start + key!!.length))
    }
}

@Composable
private fun MicButton(voiceState: Page4VoiceState, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 122.dp, height = 126.dp)
            .clickable(
                role = Role.Button,
                enabled = voiceState != Page4VoiceState.PROCESSING,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(122.dp)
                .background(Page4Colors.MicCircle, CircleShape),
        )
        Image(
            painter = painterResource(R.drawable.page4_speak),
            contentDescription = null,
            modifier = Modifier
                .size(94.dp),
        )
        Text(
            text = "말하기",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            fontFamily = Serif,
            fontSize = 28.sp,
            lineHeight = 30.sp,
            color = Color.White,
        )
    }
}

@Composable
private fun VoiceStatus(voiceState: Page4VoiceState, modifier: Modifier = Modifier) {
    val (dotColor, label) = when (voiceState) {
        Page4VoiceState.LISTENING -> Page4Colors.Listening to "듣는 중..."
        Page4VoiceState.PROCESSING -> Page4Colors.Processing to "확인 중..."
        Page4VoiceState.IDLE -> Page4Colors.Idle to "버튼을 누르고 말씀해 주세요"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(11.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Page4Colors.Ink)
    }
}

// ---------- 미리보기 (Android Studio 오른쪽 Split/Design 화면에서 확인) ----------

@Preview(name = "4페이지 - 듣는 중", widthDp = 411, heightDp = 731, showBackground = true)
@Composable
private fun OrderConfirmScreenPreview() {
    val items = listOf(
        Page4CartItem("아메리카노", "ICE", 4500, 2, R.drawable.page4_americano),
    )
    OrderConfirmScreen(
        items = items,
        messages = listOf(
            page4TotalMessage(items.sumOf { it.lineTotal }),
            Page4ChatMessage("계산할게요", fromUser = true),
        ),
        voiceState = Page4VoiceState.LISTENING,
        onMicClick = {},
        onPayClick = {},
        onHomeClick = {},
    )
}

@Preview(name = "4페이지 - 메뉴 여러 개", widthDp = 411, heightDp = 731, showBackground = true)
@Composable
private fun OrderConfirmScreenManyPreview() {
    val items = listOf(
        Page4CartItem("아메리카노", "ICE", 4500, 2, R.drawable.page4_americano, size = "R"),
        Page4CartItem("카페라떼", "HOT", 5000, 1, R.drawable.page4_americano, size = "L"),
    )
    OrderConfirmScreen(
        items = items,
        messages = listOf(page4TotalMessage(items.sumOf { it.lineTotal })),
        voiceState = Page4VoiceState.IDLE,
        onMicClick = {},
        onPayClick = {},
        onHomeClick = {},
    )
}
