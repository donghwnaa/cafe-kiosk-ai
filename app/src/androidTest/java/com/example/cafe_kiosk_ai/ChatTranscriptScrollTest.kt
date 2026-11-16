package com.example.cafe_kiosk_ai

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTranscriptScrollTest {
    @Test fun followsNewAndEditedMessagesAfterLayout() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scroll: ScrollView
            lateinit var transcript: ChatTranscript
            val messages = mutableListOf(ChatMessage(false, "무엇을 주문하시겠어요?", ChatCard.Menu))
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.screen_container)
                val screen = activity.layoutInflater.inflate(R.layout.conversation_ui, container, false)
                container.addView(screen)
                scroll = screen.findViewById(R.id.chat_scroll)
                transcript = ChatTranscript(screen) { false }
                transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY == 0 && scroll.canScrollVertically(1) }

            scenario.onActivity {
                repeat(12) { messages.add(ChatMessage(false, "이전 주문 안내 ${it + 1}")) }
                transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY > 0 && !scroll.canScrollVertically(1) }

            scenario.onActivity {
                scroll.scrollTo(0, 0)
                transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY == 0 && scroll.canScrollVertically(1) }

            scenario.onActivity {
                messages.add(ChatMessage(true, "아이스 아메리카노 주세요."))
                transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY > 0 && !scroll.canScrollVertically(1) }

            scenario.onActivity {
                scroll.scrollTo(0, 0)
                messages.add(ChatMessage(false, "어떤 크기로 드릴까요?"))
                transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY > 0 && !scroll.canScrollVertically(1) }

            var previousBottom = 0
            val editedText = "주문하신 메뉴와 크기를 확인해 주세요. ".repeat(45)
            scenario.onActivity {
                previousBottom = scroll.scrollY
                scroll.scrollTo(0, 0)
                messages[messages.lastIndex] = ChatMessage(false, editedText)
                transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY > previousBottom && !scroll.canScrollVertically(1) }
            scenario.onActivity {
                val latestRow = scroll.getChildAt(0) as ViewGroup
                val content = latestRow.getChildAt(latestRow.childCount - 1) as ViewGroup
                val bubble = (content.getChildAt(content.childCount - 1) as ViewGroup).getChildAt(0) as TextView
                assertEquals(editedText, bubble.text.toString())
            }

            scenario.onActivity {
                scroll.scrollTo(0, 0)
                repeat(3) {
                    messages.add(ChatMessage(false, "추가 응답 ${it + 1}"))
                    transcript.render(messages, PendingQuestion.NONE, restoreOffset = null)
                }
            }
            await(scenario) { scroll.scrollY > 0 && !scroll.canScrollVertically(1) }

            scenario.onActivity {
                messages.add(ChatMessage(false, "상품을 확인해 주세요.", ChatCard.ItemConfirmation(emptyList())))
                transcript.render(messages, PendingQuestion.ITEM_CONFIRMATION, restoreOffset = null)
            }
            await(scenario) { scroll.scrollY > 0 && !scroll.canScrollVertically(1) }
            scenario.onActivity {
                scroll.scrollTo(0, 0)
                val confirmationIndex = messages.lastIndex
                messages.add(ChatMessage(false, "음성을 인식하지 못했습니다."))
                transcript.render(messages, PendingQuestion.ITEM_CONFIRMATION, null, confirmationIndex)
            }
            await(scenario) { scroll.scrollY > 0 && !scroll.canScrollVertically(1) }
            scenario.onActivity {
                val matches = arrayListOf<View>()
                scroll.findViewsWithText(matches, "맞아요", View.FIND_VIEWS_WITH_TEXT)
                assertTrue(matches.any { it is Button && it.isEnabled })
            }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        do {
            var matched = false
            scenario.onActivity { matched = condition() }
            if (matched) return
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        scenario.onActivity { assertTrue("Unexpected scroll position", condition()) }
    }
}
