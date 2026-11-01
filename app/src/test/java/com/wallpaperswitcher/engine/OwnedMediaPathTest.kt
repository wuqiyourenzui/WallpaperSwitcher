package com.wallpaperswitcher.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "哪些文件是我们自己创建的、删媒体行时可以一起删" —— **只有一份清单**。
 *
 * 这里锁住的是一个真实回归：压缩包导入把解压物放在 `files/archives/`，
 * `OwnedMediaCleaner.MANAGED_DIRS` 也加了 `archives`（孤儿清理会扫它），
 * 但 `StorageController` 删单行时另写了一份前缀白名单、只认 `rss/` 和 `online/`
 * —— 于是**删分组 / 删图片时，压缩包解出来的图片留在私有目录里不删**。
 *
 * 现在删除路径改走 [OwnedMediaCleaner.isManagedPath]，这份测试守住
 * "每个 MANAGED_DIRS 都能被删" 以及 "用户文件永远不能"。
 */
class OwnedMediaPathTest {

    private val root = "/data/user/0/com.wallpaperswitcher/files"

    @After
    fun tearDown() {
        // 静态状态：清掉，避免影响同 JVM 里的其它测试。
        OwnedMediaCleaner.rememberRoot("")
    }

    @Test
    fun `every managed directory is deletable`() {
        OwnedMediaCleaner.rememberRoot(root)
        for (dir in OwnedMediaCleaner.MANAGED_DIRS) {
            assertTrue(
                "files/$dir/ 里的文件必须可删（清单与删除白名单不能漂移）",
                OwnedMediaCleaner.isManagedPath("$root/$dir/001.jpg"),
            )
        }
    }

    @Test
    fun `the archive directory in particular is deletable`() {
        OwnedMediaCleaner.rememberRoot(root)
        assertTrue(
            "压缩包解出来的图片在 files/archives/ —— 这正是回归发生的地方",
            OwnedMediaCleaner.isManagedPath("$root/archives/001.jpg"),
        )
    }

    @Test
    fun `user files are never deletable`() {
        OwnedMediaCleaner.rememberRoot(root)
        // 相册 / 自选目录 / 别的应用目录：一律不能碰
        assertFalse(OwnedMediaCleaner.isManagedPath("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertFalse(OwnedMediaCleaner.isManagedPath("/storage/emulated/0/Pictures/b.jpg"))
        assertFalse(OwnedMediaCleaner.isManagedPath("$root/../shared_prefs/x.xml"))
        assertFalse(OwnedMediaCleaner.isManagedPath("$root/unknown_dir/a.jpg"))
    }

    @Test
    fun `nothing is deletable before the root is known`() {
        // 没登记过根目录时保守返回 false：宁可留着，也不能误删。
        OwnedMediaCleaner.rememberRoot("")
        assertFalse(OwnedMediaCleaner.isManagedPath("$root/archives/001.jpg"))
    }
}
