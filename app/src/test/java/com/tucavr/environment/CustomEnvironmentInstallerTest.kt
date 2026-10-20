package com.tucavr.environment

import com.tucavr.EnvironmentDefinition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class CustomEnvironmentInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDao : CustomEnvironmentDao {
        val rows = mutableListOf<CustomEnvironment>()

        override suspend fun getAll(): List<CustomEnvironment> = rows.toList()

        override suspend fun insert(environment: CustomEnvironment) {
            rows.add(environment)
        }
    }

    private val registered = mutableListOf<EnvironmentDefinition>()
    private val dao = FakeDao()

    private fun installer(
        root: File,
        validate: (String) -> String,
    ) = CustomEnvironmentInstaller(root, dao, validate) { registered.add(it) }

    private fun glb() = ByteArrayInputStream(byteArrayOf(1, 2, 3))

    @Test
    fun `rejected model leaves nothing behind and is neither persisted nor registered`() =
        runTest {
            val root = tmp.newFolder("environments")

            val result = installer(root) { "env_error_too_many_triangles" }.install("Big", glb())

            assertEquals(InstallResult.Rejected("env_error_too_many_triangles"), result)
            assertEquals(emptyList<String>(), root.list()!!.toList())
            assertTrue(dao.rows.isEmpty())
            assertTrue(registered.isEmpty())
        }

    @Test
    fun `failed copy leaves nothing behind`() =
        runTest {
            val root = tmp.newFolder("environments")
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("disk gone")
                }

            val result = installer(root) { CustomEnvironmentInstaller.VALID_MESSAGE_KEY }.install("Broken", broken)

            assertEquals(InstallResult.Failed, result)
            assertEquals(emptyList<String>(), root.list()!!.toList())
        }

    @Test
    fun `valid model is copied and registered with a config pointing at it`() =
        runTest {
            val root = tmp.newFolder("environments")

            val result = installer(root) { CustomEnvironmentInstaller.VALID_MESSAGE_KEY }.install("My Room\nx", glb())

            val definition = (result as InstallResult.Installed).definition
            val model = File(root, "${definition.id}/model.glb")
            assertEquals(listOf<Byte>(1, 2, 3), model.readBytes().toList())
            assertEquals(model.absolutePath, definition.modelFile)
            assertEquals("My Room x", definition.name)
            assertEquals(listOf(definition), registered)
            assertEquals(listOf(definition.id), dao.rows.map { it.id })
        }

    @Test
    fun `registerInstalled restores persisted environments and skips missing directories`() =
        runTest {
            val root = tmp.newFolder("environments")
            val inst = installer(root) { CustomEnvironmentInstaller.VALID_MESSAGE_KEY }
            val kept = (inst.install("Kept", glb()) as InstallResult.Installed).definition
            dao.rows.add(CustomEnvironment(id = "ghost", name = "Ghost"))
            registered.clear()

            inst.registerInstalled()

            assertEquals(listOf(kept), registered)
        }
}
