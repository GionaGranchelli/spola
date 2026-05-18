package com.example.app

import com.example.api.UserService
import com.example.core.UserServiceImpl

fun main() {
    val service: UserService = UserServiceImpl()
    val user = service.getUser("1")
    println("User: ${user.name}")
}
