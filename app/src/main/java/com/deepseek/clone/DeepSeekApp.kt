package com.deepseek.clone

import android.app.Application
import androidx.room.Room
import com.deepseek.clone.data.importer.ChatZipManager
import com.deepseek.clone.data.local.AppDatabase
import com.deepseek.clone.data.local.SecureStore
import com.deepseek.clone.data.remote.NetworkModule
import com.deepseek.clone.data.repository.Repository

/** 简单的服务定位器（工程规模无需引入 Hilt） */
class ServiceLocator(app: Application) {
    val store = SecureStore(app)
    val db = Room.databaseBuilder(app, AppDatabase::class.java, "deepseek.db")
        .fallbackToDestructiveMigration()
        .build()
    val dao = db.chatDao()

    lateinit var repository: Repository
        private set
    lateinit var zipManager: ChatZipManager
        private set

    fun init(onAuthError: (String) -> Unit) {
        NetworkModule.configure(
            apiKeyProvider = { store.token },
            profileProvider = { store.currentProfile() },
            onAuthError = onAuthError
        )
        repository = Repository(NetworkModule.api, dao, store)
        zipManager = ChatZipManager(app, dao)
    }
}

class DeepSeekApp : Application() {
    lateinit var services: ServiceLocator
        private set
    lateinit var readAloud: com.deepseek.clone.util.ReadAloudHelper
        private set
    /** 鉴权错误提示（由 MainActivity 设置为 Toast） */
    var authErrorMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator(this)
        readAloud = com.deepseek.clone.util.ReadAloudHelper(this)
        // API Key 失效/余额不足：清除密钥，UI 跳回登录页
        services.init(onAuthError = { msg ->
            authErrorMessage = msg
            services.store.token = null
        })
    }

    override fun onTerminate() {
        super.onTerminate()
        if (::readAloud.isInitialized) readAloud.destroy()
    }
}
