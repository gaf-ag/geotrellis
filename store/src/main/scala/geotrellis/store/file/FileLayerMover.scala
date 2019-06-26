package geotrellis.store.file

import geotrellis.layer.{Boundable, Bounds}
import geotrellis.store
import geotrellis.store._
import geotrellis.store.AttributeStore.Fields
import geotrellis.store.avro.AvroRecordCodec
import geotrellis.util.{Component, Filesystem}

import spray.json.JsonFormat

import java.io.File

import scala.reflect.ClassTag


object FileLayerMover {
  def apply(sourceAttributeStore: FileAttributeStore, targetAttributeStore: FileAttributeStore): LayerMover[LayerId] =
    new LayerMover[LayerId] {
      def move[
        K: AvroRecordCodec: Boundable: JsonFormat: ClassTag,
        V: AvroRecordCodec: ClassTag,
        M: JsonFormat: Component[?, Bounds[K]]
      ](from: LayerId, to: LayerId): Unit = {
        if(targetAttributeStore.layerExists(to))
          throw new LayerExistsError(to)

        val sourceMetadataFile = sourceAttributeStore.attributeFile(from, Fields.metadata)
        if(!sourceMetadataFile.exists) throw new LayerNotFoundError(from)

        // Read the metadata file out.
        val LayerAttributes(header, metadata, keyIndex, writerSchema) = try {
          sourceAttributeStore.readLayerAttributes[FileLayerHeader, M, K](from)
        } catch {
          case e: AttributeNotFoundError => throw new LayerReadError(from).initCause(e)
        }

        // Move over any other attributes
        for((attributeName, file) <- sourceAttributeStore.attributeFiles(to)) {
          if(file.getAbsolutePath != sourceMetadataFile.getAbsolutePath) {
            val source = file.getAbsolutePath
            val target = targetAttributeStore.attributeFile(to, attributeName).getAbsolutePath
            Filesystem.move(source, target)
          }
        }

        val sourceLayerPath = new File(sourceAttributeStore.catalogPath, header.path)
        val targetHeader = header.copy(path = LayerPath(to))

        targetAttributeStore.writeLayerAttributes(to, targetHeader, metadata, keyIndex, writerSchema)

        // Delete the metadata file in the source
        sourceMetadataFile.delete()

        // Move all the elements
        val targetLayerPath = Filesystem.ensureDirectory(store.file.LayerPath(targetAttributeStore.catalogPath, to))
        sourceLayerPath
          .listFiles()
          .foreach { f =>
            val target = new File(targetLayerPath, f.getName)
            Filesystem.move(f, target)
          }

        // Clear the caches
        sourceAttributeStore.clearCache()
        targetAttributeStore.clearCache()
      }
    }

  def apply(catalogPath: String): LayerMover[LayerId] =
    apply(FileAttributeStore(catalogPath))

  def apply(attributeStore: FileAttributeStore): LayerMover[LayerId] =
    apply(attributeStore, attributeStore)

  def apply(sourceCatalogPath: String, targetCatalogPath: String): LayerMover[LayerId] =
    apply(FileAttributeStore(sourceCatalogPath), FileAttributeStore(targetCatalogPath))
}