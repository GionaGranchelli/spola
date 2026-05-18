package com.example.api

data class User(val id: String, val name: String)

interface UserService {
    fun getUser(id: String): User
    fun listUsers(): List<User>
}
