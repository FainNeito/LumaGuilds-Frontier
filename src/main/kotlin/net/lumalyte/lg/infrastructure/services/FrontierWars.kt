package net.lumalyte.lg.infrastructure.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.*
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class FrontierWarRecord(val id: UUID,val attacker: UUID,val defender: UUID,
    val attackingFoundation: UUID,val defendingFoundation: UUID,val starts: Instant,val ends: Instant,
    val reason: String?=null,val winner: UUID?=null,val peaceFrom: UUID?=null) {
    fun involves(guild: UUID)=guild==attacker || guild==defender
    fun other(guild: UUID): UUID { require(involves(guild));return if(guild==attacker) defender else attacker }
}

/** Separate, atomically persisted Frontier wars. Never alters the global protection schedule. */
class FrontierWars(private val file: File,private val config: FrontierConfig,
    private val claims: ClaimRepository,private val guilds: GuildService,private val members: MemberService,
    private val relations: RelationService,private val announce: (FrontierWarRecord,String)->Unit,
    private val clock: ()->Instant = Instant::now,
    private val notice: Duration = Duration.ofMinutes(15),private val cooldown: Duration = Duration.ofHours(24)) {
    private var state=YamlConfiguration().apply { if(file.exists()) load(file) }
    init { records() } // Malformed records fail closed at startup.
    @Synchronized fun records(): List<FrontierWarRecord> = state.getConfigurationSection("wars")?.getKeys(false)?.map { id ->
        val p="wars.$id"
        fun uuid(field:String)=UUID.fromString(state.getString("$p.$field")!!)
        FrontierWarRecord(UUID.fromString(id),uuid("attacker"),uuid("defender"),uuid("attacking-foundation"),uuid("defending-foundation"),
            Instant.parse(state.getString("$p.starts")!!),Instant.parse(state.getString("$p.ends")!!),state.getString("$p.reason"),
            state.getString("$p.winner")?.let(UUID::fromString),state.getString("$p.peace-from")?.let(UUID::fromString))
    } ?: emptyList()
    fun current(guild: UUID)=records().firstOrNull { it.reason==null && it.involves(guild) }
    private fun foundation(guild: UUID)=claims.getByTeam(guild).filter { it.teamId==guild }
        .minWithOrNull(compareBy<Claim> { it.creationTime }.thenBy { it.id })?.id
    private fun authorized(actor: UUID,guild: UUID)=members.getMember(actor,guild)!=null && members.hasPermission(actor,guild,RankPermission.DECLARE_WAR)
    private fun save(change: (YamlConfiguration)->Unit) {
        val next=YamlConfiguration().apply { loadFromString(state.saveToString()) };change(next)
        val temporary=file.toPath().resolveSibling(file.name+".tmp")
        Files.writeString(temporary,next.saveToString())
        Files.move(temporary,file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        state=next
    }
    @Synchronized fun declare(actor: UUID,attacker: UUID,defender: UUID): String {
        tick()
        if(!authorized(actor,attacker)) return "Your rank cannot declare wars."
        if(attacker==defender || guilds.getGuild(attacker)==null || guilds.getGuild(defender)==null) return "Choose another existing guild."
        if(relations.getRelationType(attacker,defender)!=RelationType.ENEMY) return "Declare this guild an enemy first."
        if(current(attacker)!=null || current(defender)!=null) return "One of these guilds already has a scheduled or active war."
        val now=clock()
        if(now.toEpochMilli()<state.getLong("cooldowns.$attacker",0)) return "Your guild is still on its war-declaration cooldown."
        val window=FrontierWarPolicy.window(config.schedule,now,notice) ?: return "Declare outside raids, at least ${notice.toMinutes()} minutes before the next raid window."
        val a=foundation(attacker) ?: return "Establish your Founding Vault first."
        val d=foundation(defender) ?: return "The enemy needs a claimed Founding Vault."
        val war=FrontierWarRecord(UUID.randomUUID(),attacker,defender,a,d,window.first,window.second)
        save { data ->
            val p="wars.${war.id}"
            mapOf("attacker" to attacker,"defender" to defender,"attacking-foundation" to a,"defending-foundation" to d,
                "starts" to war.starts,"ends" to war.ends).forEach { (k,v)->data.set("$p.$k",v.toString()) }
            data.set("cooldowns.$attacker",now.plus(cooldown).toEpochMilli())
        }
        announce(war,"War declared: ${name(attacker)} against ${name(defender)}. Starts ${war.starts.atZone(config.schedule.zone)}. Destroy the enemy Founding Vault to win.")
        return "War declared for the next raid window."
    }
    fun name(guild: UUID)=guilds.getGuild(guild)?.name ?: "Disbanded guild"
    private fun finish(war: FrontierWarRecord,winner: UUID?,reason: String) {
        if(records().firstOrNull { it.id==war.id }?.reason!=null) return
        save { it.set("wars.${war.id}.reason",reason);it.set("wars.${war.id}.winner",winner?.toString()) }
        announce(war.copy(reason=reason,winner=winner),"${name(war.attacker)} vs ${name(war.defender)}: ${winner?.let { "${name(it)} wins" } ?: "draw"}. $reason")
    }
    @Synchronized fun surrender(actor: UUID,guild: UUID): Boolean {
        tick();if(!authorized(actor,guild)) return false
        val war=current(guild) ?: return false
        finish(war,war.other(guild),"${name(guild)} surrendered.");return true
    }
    @Synchronized fun peace(actor: UUID,guild: UUID): Boolean {
        tick();if(!authorized(actor,guild)) return false
        val war=current(guild) ?: return false
        if(war.peaceFrom==war.other(guild)) finish(war,null,"Both guilds agreed to peace.")
        else {
            save { it.set("wars.${war.id}.peace-from",guild.toString()) }
            announce(war,"${name(guild)} offered peace. The opposing guild can accept from its war menu.")
        };return true
    }
    /** Called after successful durable claim release; unattributed/third-party destruction is not a credited victory. */
    @Synchronized fun vaultFallen(owner: FrontierOwner,attackingGuilds: Set<UUID>) {
        if(owner.type!=FrontierOwnerType.GUILD) return
        val war=current(owner.id) ?: return
        if(!FrontierWarPolicy.active(war.starts,war.ends,clock()) || !config.schedule.stateAt(clock()).active) return
        val opponent=war.other(owner.id)
        finish(war,opponent.takeIf { it in attackingGuilds },if(opponent in attackingGuilds) "Enemy Founding Vault destroyed." else "Vault fell without an attributed opposing-guild attacker.")
    }
    @Synchronized fun tick() {
        val now=clock()
        records().filter { it.reason==null }.forEach { war ->
            if(!now.isBefore(war.ends)) { finish(war,null,"Raid window ended without a decisive vault destruction.");return@forEach }
            val a=foundation(war.attacker)==war.attackingFoundation
            val d=foundation(war.defender)==war.defendingFoundation
            if(!a || !d) {
                val winner=if(now.isBefore(war.starts) && a!=d) { if(a) war.attacker else war.defender } else null
                finish(war,winner,if(now.isBefore(war.starts)) "Founding territory removed before battle." else "Territory changed without a recorded opposing vault destruction.")
                return@forEach
            }
            if(!now.isBefore(war.starts) && !state.getBoolean("wars.${war.id}.announced",false)) {
                save { it.set("wars.${war.id}.announced",true) }
                announce(war,"War is active: ${name(war.attacker)} vs ${name(war.defender)}. Defend your Founding Vault.")
            }
        }
    }
}
