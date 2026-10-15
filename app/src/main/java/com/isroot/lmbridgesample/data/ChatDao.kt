package com.isroot.lmbridgesample.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations WHERE modelKey = :modelKey ORDER BY createdAt")
    suspend fun conversations(modelKey: String): List<ConversationEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY position")
    suspend fun messages(conversationId: String): List<MessageEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    /** 메시지는 FK CASCADE로 함께 삭제된다. */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    /** 이전 실행에서 생성 도중 끊긴 응답을 정리한다. */
    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE status = 'STREAMING'")
    suspend fun markStreamingInterrupted(): Int
}
