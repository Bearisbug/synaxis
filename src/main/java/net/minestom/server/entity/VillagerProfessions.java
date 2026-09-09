package net.minestom.server.entity;

/**
 * This class contains all the generated constants for {@link VillagerProfession}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
sealed interface VillagerProfessions permits VillagerProfession {
    VillagerProfession NONE = VillagerProfessionImpl.get(VillagerProfessionKeys.NONE);

    VillagerProfession ARMORER = VillagerProfessionImpl.get(VillagerProfessionKeys.ARMORER);

    VillagerProfession BUTCHER = VillagerProfessionImpl.get(VillagerProfessionKeys.BUTCHER);

    VillagerProfession CARTOGRAPHER = VillagerProfessionImpl.get(VillagerProfessionKeys.CARTOGRAPHER);

    VillagerProfession CLERIC = VillagerProfessionImpl.get(VillagerProfessionKeys.CLERIC);

    VillagerProfession FARMER = VillagerProfessionImpl.get(VillagerProfessionKeys.FARMER);

    VillagerProfession FISHERMAN = VillagerProfessionImpl.get(VillagerProfessionKeys.FISHERMAN);

    VillagerProfession FLETCHER = VillagerProfessionImpl.get(VillagerProfessionKeys.FLETCHER);

    VillagerProfession LEATHERWORKER = VillagerProfessionImpl.get(VillagerProfessionKeys.LEATHERWORKER);

    VillagerProfession LIBRARIAN = VillagerProfessionImpl.get(VillagerProfessionKeys.LIBRARIAN);

    VillagerProfession MASON = VillagerProfessionImpl.get(VillagerProfessionKeys.MASON);

    VillagerProfession NITWIT = VillagerProfessionImpl.get(VillagerProfessionKeys.NITWIT);

    VillagerProfession SHEPHERD = VillagerProfessionImpl.get(VillagerProfessionKeys.SHEPHERD);

    VillagerProfession TOOLSMITH = VillagerProfessionImpl.get(VillagerProfessionKeys.TOOLSMITH);

    VillagerProfession WEAPONSMITH = VillagerProfessionImpl.get(VillagerProfessionKeys.WEAPONSMITH);
}
