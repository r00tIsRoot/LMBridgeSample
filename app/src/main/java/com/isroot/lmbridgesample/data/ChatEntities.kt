package com.isroot.lmbridgesample.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.isroot.lmbridgesample.Role

/** 말풍선의 생성 상태. 스트리밍 중 앱이 죽어도 다음 실행에서 상태를 판별할 수 있게 저장한다. */
enum class MessageStatus {
    /** 정상 완료(사용자 메시지 포함). */
    COMPLETE,

    /** 응답 생성 중. 앱 재시작 시 남아 있으면 [INTERRUPTED]로 정리된다. */
    STREAMING,

    /** 사용자 중지·모델 전환·프로세스 종료로 끊긴 응답. */
    INTERRUPTED,

    /** 엔진 오류로 끝난 응답. 히스토리 주입에서 제외한다. */
    ERROR,
}

/** 채팅방 1개. 모델별로 묶인다([modelKey] = SampleModel.key). */
@Entity(tableName = "conversations", indices = [Index("modelKey")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val modelKey: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 말풍선 1개. [position]은 방 안에서의 순서(0부터)이며, id는 클라이언트가 만든 UUID라
 * 삽입 결과를 기다리지 않고 스트리밍 중 갱신(upsert)할 수 있다.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val position: Int,
    val role: Role,
    val text: String,
    val thought: String,
    val stats: String?,
    val status: MessageStatus,
    val createdAt: Long,
)
