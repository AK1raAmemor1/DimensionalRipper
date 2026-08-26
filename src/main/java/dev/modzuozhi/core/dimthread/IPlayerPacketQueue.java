package dev.modzuozhi.core.dimthread;

/**
 * 第2步：网络包按玩家分派到维度线程——{@code ServerLevel} 的每维度玩家包队列访问接口。
 * <p>
 * 由 {@code ServerLevelPlayerPacketMixin} 注入实现，供 {@code PacketUtilsDimMixin}
 * （包处理重定向）与 {@code ServerLevelPlayerPacketMixin}（tick 收口 drain）使用。
 */
public interface IPlayerPacketQueue {
    /** 获取本维度的玩家包处理队列。 */
    PlayerPacketQueue modzuozhi$playerPacketQueue();
}
