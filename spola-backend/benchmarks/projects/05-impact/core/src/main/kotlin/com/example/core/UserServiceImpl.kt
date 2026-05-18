package com.example.core

import com.example.api.User
import com.example.api.UserService

class UserServiceImpl : UserService {
    private val users = mapOf(
        "1" to User("1", "Alice"),
        "2" to User("2", "Bob"),
    )

    override fun getUser(id: String): User {
        return users[id] ?: throw IllegalArgumentException("User not found: $id")
    }

    override fun listUsers(): List<User> {
        return users.values.toList()
    }
}
