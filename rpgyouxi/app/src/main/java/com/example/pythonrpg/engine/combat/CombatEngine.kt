package com.example.pythonrpg.engine.combat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// 1. 怪物静态配置快照
data class MonsterSnapshot(
    val monsterId: Long,
    val typeId: String,
    val isBoss: Boolean,
    val level: Int,
    val maxHp: Int,
    val strength: Int,
    val agility: Int,
    val defense: Int
)

// 2. 战斗状态机实时会话
data class BattleSession(
    val sessionId: Long,
    val adventurerId: Long,
    val monsterSnapshot: MonsterSnapshot,
    var adventurerCurrentHp: Int,
    var monsterCurrentHp: Int,
    var comboCount: Int = 0,
    var consecutiveTimeoutCount: Int = 0,
    var isManualMode: Boolean = true,
    val startedAtTick: Long,
    var damageMultiplier: Float = 1.0f // MP 技能增益系数缓冲
)

// 3. 答题结算回执
data class AnswerResult(
    val correct: Boolean,
    val damageDealt: Int,
    val damageTaken: Int,
    val isCritical: Boolean,
    val newComboCount: Int,
    val critChance: Float,
    val sessionEnded: Boolean,
    val endReason: String?
)

// 4. 战斗终结原因
enum class BattleEndReason {
    MONSTER_DEFEATED, ADVENTURER_INJURED, FLEE, TIMEOUT_ESCAPE
}

// 5. 战斗全局终结事件广播
data class BattleEndEvent(
    val sessionId: Long,
    val reason: BattleEndReason,
    val expGained: Int,
    val drops: Map<String, Int>
)

// 6. 外部持久属性查询供应者接口
interface CombatDependencyProvider {
    fun getAdventurerTotalAttack(adventurerId: Long): Int
    fun getAdventurerTotalDefense(adventurerId: Long): Int
    fun getAdventurerHp(adventurerId: Long): Int
    fun getAdventurerMp(adventurerId: Long): Int
    fun deductAdventurerMp(adventurerId: Long, amount: Int): Boolean
}

// 技能枚举池
enum class MpSkill {
    EXTEND_TIME,   // 延长时间（+5 秒倒计时），消耗 5 MP
    ELIMINATE,     // 排除两个错误选项，消耗 8 MP
    AUTO_CORRECT   // 自动答对当前题（Boss 战禁用），消耗 15 MP
}

/**
 * CombatEngine - 自动/手动答题对砍战斗引擎
 */
open class CombatEngine(private val provider: CombatDependencyProvider) {
    private val activeSessions = ConcurrentHashMap<Long, BattleSession>()
    private val _battleEndFlow = MutableSharedFlow<BattleEndEvent>(extraBufferCapacity = 64)
    val battleEndFlow: SharedFlow<BattleEndEvent> = _battleEndFlow
    private val nextSessionId = AtomicLong(1L)

    // 提供内部/测试使用的随机数生成器注入
    var randomGenerator: Random = Random.Default

    /**
     * 启动一场战斗
     */
    fun startBattle(adventurerId: Long, monster: MonsterSnapshot, startedTick: Long): Long {
        if (activeSessions.values.any { it.adventurerId == adventurerId }) {
            throw IllegalStateException("Adventurer is already in a battle session")
        }

        val currentHp = provider.getAdventurerHp(adventurerId)
        val sessionId = nextSessionId.getAndIncrement()
        val session = BattleSession(
            sessionId = sessionId,
            adventurerId = adventurerId,
            monsterSnapshot = monster,
            adventurerCurrentHp = currentHp,
            monsterCurrentHp = monster.maxHp,
            startedAtTick = startedTick
        )

        activeSessions[sessionId] = session
        return sessionId
    }

    /**
     * 获取指定战斗会话
     */
    fun getSession(sessionId: Long): BattleSession? = activeSessions[sessionId]

    /**
     * 获取所有活跃会话ID
     */
    fun getActiveSessionIds(): List<Long> = activeSessions.keys.toList()

    /**
     * 内部结算收尾
     */
    private fun endBattle(sessionId: Long, reason: BattleEndReason) {
        val session = activeSessions.remove(sessionId) ?: return
        
        val expGained = if (reason == BattleEndReason.MONSTER_DEFEATED) {
            session.monsterSnapshot.level * 20
        } else {
            0
        }

        val drops = if (reason == BattleEndReason.MONSTER_DEFEATED) {
            calculateDrops(session.monsterSnapshot, session.isManualMode, session.comboCount)
        } else {
            emptyMap()
        }

        val event = BattleEndEvent(sessionId, reason, expGained, drops)
        _battleEndFlow.tryEmit(event)
    }

    /**
     * 切换手动/自动挂机模式
     */
    fun setAutoMode(sessionId: Long, auto: Boolean) {
        val session = activeSessions[sessionId] ?: return
        session.isManualMode = !auto
    }

    /**
     * 实时计算答题倒计时时长限制
     */
    fun getCountdownSeconds(sessionId: Long): Float {
        val session = activeSessions[sessionId] ?: return 0.0f
        val agility = session.monsterSnapshot.agility.coerceAtLeast(0)
        return 6.0f + 10.0f / (1.0f + agility)
    }

    /**
     * 使用 MP 技能奥义
     */
    fun useMpSkill(sessionId: Long, skill: MpSkill, currentMp: Int): Pair<Boolean, Int> {
        val session = activeSessions[sessionId] ?: return Pair(false, 0)

        // 1. 耗蓝字典映射
        val requiredMp = when (skill) {
            MpSkill.EXTEND_TIME -> 5
            MpSkill.ELIMINATE -> 8
            MpSkill.AUTO_CORRECT -> 15
        }

        // 2. Boss 战高级奥义强免疫拦截
        if (skill == MpSkill.AUTO_CORRECT && session.monsterSnapshot.isBoss) {
            return Pair(false, 0)
        }

        // 3. 蓝量余额校验
        if (currentMp < requiredMp) {
            return Pair(false, 0)
        }

        // 4. 扣减及应用
        val success = provider.deductAdventurerMp(session.adventurerId, requiredMp)
        return if (success) {
            if (skill == MpSkill.EXTEND_TIME) {
                // 可在此增加延长时间逻辑，接口设计上主要体现扣减并返回
            }
            Pair(true, requiredMp)
        } else {
            Pair(false, 0)
        }
    }

    /**
     * 手动提交玩家答题判定结果
     */
    fun submitAnswer(sessionId: Long, isCorrect: Boolean): AnswerResult {
        val session = activeSessions[sessionId] ?: throw IllegalArgumentException("Session not found")
        val monster = session.monsterSnapshot

        val att = provider.getAdventurerTotalAttack(session.adventurerId)
        val def = provider.getAdventurerTotalDefense(session.adventurerId)

        if (isCorrect) {
            // 答对分支：连击累加，超时归零
            session.comboCount += 1
            session.consecutiveTimeoutCount = 0

            // 确定性连击伤害倍率计算
            val mCombo = when {
                session.comboCount < 3 -> 1.0f
                session.comboCount in 3..4 -> 1.5f
                else -> 2.0f
            }

            val baseDmg = Math.max(1, att - monster.defense)
            val finalDmg = (baseDmg * mCombo * session.damageMultiplier).toInt()
            val isCrit = mCombo >= 2.0f

            val critChance = when {
                session.comboCount < 3 -> 0.0f
                session.comboCount in 3..4 -> 0.3f
                else -> 0.7f
            }

            session.monsterCurrentHp = (session.monsterCurrentHp - finalDmg).coerceAtLeast(0)

            val ended = session.monsterCurrentHp <= 0
            if (ended) {
                endBattle(sessionId, BattleEndReason.MONSTER_DEFEATED)
                return AnswerResult(
                    correct = true, damageDealt = finalDmg, damageTaken = 0,
                    isCritical = isCrit, newComboCount = session.comboCount,
                    critChance = critChance, sessionEnded = true, endReason = "MONSTER_DEAD"
                )
            } else {
                return AnswerResult(
                    correct = true, damageDealt = finalDmg, damageTaken = 0,
                    isCritical = isCrit, newComboCount = session.comboCount,
                    critChance = critChance, sessionEnded = false, endReason = null
                )
            }
        } else {
            // 答错分支：连击熔减，超时归零
            session.comboCount = 0
            session.consecutiveTimeoutCount = 0

            // 怪物反击伤害结算公式（强制 1 点物理保底）
            val taken = Math.max(1.0f, monster.strength * 0.3f - def * 0.3f).toInt()
            session.adventurerCurrentHp = (session.adventurerCurrentHp - taken).coerceAtLeast(0)

            val ended = session.adventurerCurrentHp <= 0
            if (ended) {
                endBattle(sessionId, BattleEndReason.ADVENTURER_INJURED)
                return AnswerResult(
                    correct = false, damageDealt = 0, damageTaken = taken,
                    isCritical = false, newComboCount = 0, critChance = 0.0f,
                    sessionEnded = true, endReason = "ADVENTURER_DEAD"
                )
            } else {
                return AnswerResult(
                    correct = false, damageDealt = 0, damageTaken = taken,
                    isCritical = false, newComboCount = 0, critChance = 0.0f,
                    sessionEnded = false, endReason = null
                )
            }
        }
    }

    /**
     * 答题倒计时归零时，外部系统调用此接口报告超时
     */
    fun reportTimeout(sessionId: Long): AnswerResult {
        val session = activeSessions[sessionId] ?: throw IllegalArgumentException("Session not found")
        val monster = session.monsterSnapshot
        val def = provider.getAdventurerTotalDefense(session.adventurerId)

        // 超时惩罚累加与连击重置
        session.consecutiveTimeoutCount += 1
        session.comboCount = 0

        // 怪物超时反击伤害结算公式（等同答错，1 点保底）
        val taken = Math.max(1.0f, monster.strength * 0.3f - def * 0.3f).toInt()
        session.adventurerCurrentHp = (session.adventurerCurrentHp - taken).coerceAtLeast(0)

        // 死斗及三连超时退避检查
        if (session.adventurerCurrentHp <= 0) {
            endBattle(sessionId, BattleEndReason.ADVENTURER_INJURED)
            return AnswerResult(
                correct = false, damageDealt = 0, damageTaken = taken,
                isCritical = false, newComboCount = 0, critChance = 0.0f,
                sessionEnded = true, endReason = "ADVENTURER_DEAD"
            )
        }

        if (session.consecutiveTimeoutCount >= 3) {
            endBattle(sessionId, BattleEndReason.TIMEOUT_ESCAPE)
            return AnswerResult(
                correct = false, damageDealt = 0, damageTaken = taken,
                isCritical = false, newComboCount = 0, critChance = 0.0f,
                sessionEnded = true, endReason = "TIMEOUT_ESCAPE"
            )
        }

        return AnswerResult(
            correct = false, damageDealt = 0, damageTaken = taken,
            isCritical = false, newComboCount = 0, critChance = 0.0f,
            sessionEnded = false, endReason = null
        )
    }

    /**
     * 主动逃跑
     */
    fun fleeBattle(sessionId: Long): BattleEndEvent {
        if (!activeSessions.containsKey(sessionId)) {
            throw IllegalArgumentException("Session not found")
        }
        val event = BattleEndEvent(sessionId, BattleEndReason.FLEE, expGained = 0, drops = emptyMap())
        activeSessions.remove(sessionId)
        _battleEndFlow.tryEmit(event)
        return event
    }

    /**
     * 自动战斗挂机推进一步
     */
    fun processAutoBattle(sessionId: Long): AnswerResult? {
        val session = activeSessions[sessionId] ?: return null
        val monster = session.monsterSnapshot

        val att = provider.getAdventurerTotalAttack(session.adventurerId)
        val def = provider.getAdventurerTotalDefense(session.adventurerId)

        // 玩家攻击怪物伤害（0.6倍系数 + 0.8~1.2 浮动）
        val baseAdv = Math.max(1.0f, att * 0.6f - monster.defense)
        val factorAdv = randomGenerator.nextDouble(0.8, 1.2).toFloat()
        val dmgAdv = (baseAdv * factorAdv).toInt()

        // 怪物攻击玩家伤害（0.6倍防御减免保底0 + 0.8~1.2 浮动）
        val baseMon = Math.max(0.0f, monster.strength.toFloat() - def * 0.6f)
        val factorMon = randomGenerator.nextDouble(0.8, 1.2).toFloat()
        val dmgMon = (baseMon * factorMon).toInt()

        // 双原子生命值写回
        session.monsterCurrentHp = (session.monsterCurrentHp - dmgAdv).coerceAtLeast(0)
        session.adventurerCurrentHp = (session.adventurerCurrentHp - dmgMon).coerceAtLeast(0)

        // 双重死亡边界核对：同归于尽算玩家败北
        if (session.monsterCurrentHp <= 0 && session.adventurerCurrentHp <= 0) {
            endBattle(sessionId, BattleEndReason.ADVENTURER_INJURED)
            return AnswerResult(
                correct = false, damageDealt = dmgAdv, damageTaken = dmgMon,
                isCritical = false, newComboCount = 0, critChance = 0.0f,
                sessionEnded = true, endReason = "ADVENTURER_DEAD"
            )
        }

        if (session.monsterCurrentHp <= 0) {
            endBattle(sessionId, BattleEndReason.MONSTER_DEFEATED)
            return AnswerResult(
                correct = true, damageDealt = dmgAdv, damageTaken = dmgMon,
                isCritical = false, newComboCount = 0, critChance = 0.0f,
                sessionEnded = true, endReason = "MONSTER_DEAD"
            )
        }

        if (session.adventurerCurrentHp <= 0) {
            endBattle(sessionId, BattleEndReason.ADVENTURER_INJURED)
            return AnswerResult(
                correct = false, damageDealt = dmgAdv, damageTaken = dmgMon,
                isCritical = false, newComboCount = 0, critChance = 0.0f,
                sessionEnded = true, endReason = "ADVENTURER_DEAD"
            )
        }

        return AnswerResult(
            correct = true, damageDealt = dmgAdv, damageTaken = dmgMon,
            isCritical = false, newComboCount = 0, critChance = 0.0f,
            sessionEnded = false, endReason = null
        )
    }

    /**
     * 计算怪物掉落物资
     */
    fun calculateDrops(monster: MonsterSnapshot, isManual: Boolean, comboCount: Int): Map<String, Int> {
        val baseDrops = mutableMapOf<String, Int>()

        if (isManual) {
            baseDrops["GOLD"] = 10 * monster.level
            baseDrops["CORE"] = 1
            
            // 手动且高连击摇点几率
            if (comboCount >= 5 && randomGenerator.nextFloat() < 0.2f) {
                baseDrops["GOLD"] = (baseDrops["GOLD"] ?: 0) + 1
                baseDrops["CORE"] = (baseDrops["CORE"] ?: 0) + 1
                baseDrops["RARE_GEM"] = 1
            }
        } else {
            // 自动挂机胜利：物理乘数系数斩断为 50% 并四舍五入
            val qtyGold = Math.round((10 * monster.level) * 0.5f)
            val qtyCore = Math.round(1.0f * 0.5f)
            
            if (qtyGold > 0) baseDrops["GOLD"] = qtyGold
            if (qtyCore > 0) baseDrops["CORE"] = qtyCore
        }

        return baseDrops
    }
}
