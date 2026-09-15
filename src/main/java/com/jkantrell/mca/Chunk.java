package com.jkantrell.mca;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import com.jkantrell.nbt.io.NBTDeserializer;
import com.jkantrell.nbt.io.NBTSerializer;
import com.jkantrell.nbt.io.NamedTag;
import com.jkantrell.nbt.tag.CompoundTag;
import com.jkantrell.nbt.tag.ListTag;
import com.jkantrell.nbt.tag.StringTag;

public class Chunk implements Iterable<Section> {

	public static final int DEFAULT_DATA_VERSION = 3839; // 2567

	private boolean partial;
	private boolean raw;
	private int xPos, zPos;
	private int lastMCAUpdate;
	private CompoundTag data;
	private int dataVersion;
	private long lastUpdate;
	private long inhabitedTime;
	private CompoundTag heightMaps;
	private CompoundTag carvingMasks;
	private Map<Integer, Section> sections = new TreeMap<>();
	private ListTag<CompoundTag> entities;
	private ListTag<CompoundTag> tileEntities;
	private ListTag<CompoundTag> tileTicks;
	private ListTag<CompoundTag> liquidTicks;
	private ListTag<ListTag<?>> lights;
	private ListTag<ListTag<?>> liquidsToBeTicked;
	private ListTag<ListTag<?>> toBeTicked;
	private ListTag<ListTag<?>> postProcessing;
	private String status;
	private CompoundTag structures;
	private ListTag<CompoundTag> blockEntities;

	Chunk(int lastMCAUpdate) { this.lastMCAUpdate = lastMCAUpdate; }

	/**
	 * Create a new chunk based on raw base data from a region file.
	 * 
	 * @param data The raw base data to be used.
	 */
	public Chunk(CompoundTag data) {
		this.data = data;
		initReferences(LoadFlags.ALL_DATA);
	}

	private void initReferences(long loadFlags) {
		if (data == null) {
			throw new NullPointerException("data cannot be null");
		}

		if ((loadFlags != LoadFlags.ALL_DATA) && (loadFlags & LoadFlags.RAW) != 0) {
			raw = true;
			return;
		}

		this.xPos = this.data.getInt("xPos");
		this.zPos = this.data.getInt("zPos");

		this.dataVersion = this.data.getInt("DataVersion");
		this.inhabitedTime = this.data.getLong("InhabitedTime");
		this.lastUpdate = this.data.getLong("LastUpdate");
		if ((loadFlags & LoadFlags.HEIGHTMAPS) != 0) {
			this.heightMaps = this.data.getCompoundTag("Heightmaps");
		}
		if ((loadFlags & LoadFlags.CARVING_MASKS) != 0) {
			this.carvingMasks = this.data.getCompoundTag("CarvingMasks");
		}
		if ((loadFlags & LoadFlags.ENTITIES) != 0) {
			this.entities = this.data.containsKey("Entities") ? this.data.getListTag("Entities").asCompoundTagList() : null;
		}
		if ((loadFlags & LoadFlags.BLOCK_TICKS) != 0) {
			this.tileTicks = this.data.containsKey("block_ticks") ? this.data.getListTag("block_ticks").asCompoundTagList() : null;
		}
		if ((loadFlags & LoadFlags.FLUID_TICKS) != 0) {
			this.liquidTicks = this.data.containsKey("fluid_ticks") ? this.data.getListTag("fluid_ticks").asCompoundTagList() : null;
		}
		if ((loadFlags & LoadFlags.LIGHTS) != 0) {
			this.lights = this.data.containsKey("Lights") ? this.data.getListTag("Lights").asListTagList() : null;
		}
		if ((loadFlags & LoadFlags.POST_PROCESSING) != 0) {
			this.postProcessing = this.data.containsKey("PostProcessing") ? this.data.getListTag("PostProcessing").asListTagList() : null;
		}
		this.status = this.data.getString("Status");
		if ((loadFlags & LoadFlags.STRUCTURES) != 0) {
			this.structures = this.data.getCompoundTag("Structures");
		}
		if ((loadFlags & (LoadFlags.BLOCK_LIGHTS | LoadFlags.BLOCK_STATES | LoadFlags.SKY_LIGHT)) != 0
				&& this.data.containsKey("sections")) {
			for (CompoundTag section : this.data.getListTag("sections").asCompoundTagList()) {
				int sectionIndex = section.getNumber("Y").byteValue();
				Section newSection = new Section(section);
				this.sections.put(sectionIndex, newSection);
			}
		}
		if ((loadFlags & LoadFlags.BLOCK_ENTITIES) != 0) {
			this.blockEntities = this.data.containsKey("block_entities") ? this.data.getListTag("block_entities").asCompoundTagList()
					: null;
		}

		// If we haven't requested the full set of data we can drop the underlying raw data to let the GC handle it.
		if (loadFlags != LoadFlags.ALL_DATA) {
			this.data = null;
			this.partial = true;
		}
	}

	/**
	 * Serializes this chunk to a <code>RandomAccessFile</code>.
	 * 
	 * @param raf  The RandomAccessFile to be written to.
	 * @param xPos The x-coordinate of the chunk.
	 * @param zPos The z-coodrinate of the chunk.
	 * @return The amount of bytes written to the RandomAccessFile.
	 * @throws UnsupportedOperationException When something went wrong during writing.
	 * @throws IOException                   When something went wrong during writing.
	 */
	public int serialize(RandomAccessFile raf, int xPos, int zPos) throws IOException {
		if (partial) {
			throw new UnsupportedOperationException("Partially loaded chunks cannot be serialized");
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
		try (BufferedOutputStream nbtOut = new BufferedOutputStream(CompressionType.ZLIB.compress(baos))) {
			new NBTSerializer(false).toStream(new NamedTag(null, updateHandle(xPos, zPos)), nbtOut);
		}
		byte[] rawData = baos.toByteArray();
		raf.writeInt(rawData.length + 1); // including the byte to store the compression type
		raf.writeByte(CompressionType.ZLIB.getID());
		raf.write(rawData);
		return rawData.length + 5;
	}

	/**
	 * Reads chunk data from a RandomAccessFile. The RandomAccessFile must already be at the correct position.
	 * 
	 * @param raf The RandomAccessFile to read the chunk data from.
	 * @throws IOException When something went wrong during reading.
	 */
	public void deserialize(RandomAccessFile raf) throws IOException { deserialize(raf, LoadFlags.ALL_DATA); }

	/**
	 * Reads chunk data from a RandomAccessFile. The RandomAccessFile must already be at the correct position.
	 * 
	 * @param raf       The RandomAccessFile to read the chunk data from.
	 * @param loadFlags A logical or of {@link LoadFlags} constants indicating what data should be loaded
	 * @throws IOException When something went wrong during reading.
	 */
	public void deserialize(RandomAccessFile raf, long loadFlags) throws IOException {
		byte compressionTypeByte = raf.readByte();
		CompressionType compressionType = CompressionType.getFromID(compressionTypeByte);
		if (compressionType == null) {
			throw new IOException("invalid compression type " + compressionTypeByte);
		}
		BufferedInputStream dis = new BufferedInputStream(compressionType.decompress(new FileInputStream(raf.getFD())));
		NamedTag tag = new NBTDeserializer(false).fromStream(dis);
		if (tag != null && tag.getTag() instanceof CompoundTag) {
			data = (CompoundTag) tag.getTag();
			initReferences(loadFlags);
		} else {
			throw new IOException("invalid data tag: " + (tag == null ? "null" : tag.getClass().getName()));
		}
	}

	/**
	 * Fetches a biome id at a specific block in this chunk.
	 * The coordinates can be absolute coordinates or relative to the region or chunk.
	 * 
	 * @param blockX The x-coordinate of the block.
	 * @param blockY The y-coordinate of the block.
	 * @param blockZ The z-coordinate of the block.
	 * @return The biome id or -1 if the biomes are not correctly initialized.
	 */
	public StringTag getBiomeAt(int blockX, int blockY, int blockZ) {
		Section section = sections.get(MCAUtil.blockToChunk(blockY));
		if (section == null) {
			return null;
		}
		return section.getBiomeAt(blockX, Math.floorMod(blockY, 16), blockZ);
	}

	@Deprecated
	public void setBiomeAt(int blockX, int blockZ, int biomeID) {

	}

	/**
	 * Sets a biome id at a specific block column.
	 * The coordinates can be absolute coordinates or relative to the region or chunk.
	 * 
	 * @param blockX  The x-coordinate of the block column.
	 * @param blockZ  The z-coordinate of the block column.
	 * @param biomeID The biome id to be set.
	 *                When set to a negative number, Minecraft will replace it with the block column's default biome.
	 */
	public void setBiomeAt(int blockX, int blockY, int blockZ, int biomeID) {

	}

	int getBiomeIndex(int biomeX, int biomeY, int biomeZ) { return biomeY * 16 + biomeZ * 4 + biomeX; }

	public int getX() { return this.xPos; }
	public int getZ() { return this.zPos; }

	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
		Section section = sections.get(MCAUtil.blockToChunk(blockY));
		if (section == null) {
			return null;
		}
		return section.getBlockStateAt(blockX, Math.floorMod(blockY, 16), blockZ);
	}

	public CompoundTag getBlockEntity(int blockX, int blockY, int blockZ) {
		if (blockEntities != null) {
			for (CompoundTag e : blockEntities) {
				if (e.getInt("x") == blockX && e.getInt("y") == blockY && e.getInt("z") == blockZ) {
					return e;
				}
			}
		}
		return null;
	}

	/**
	 * Sets a block state at a specific location.
	 * The block coordinates can be absolute or relative to the region or chunk.
	 * 
	 * @param blockX The x-coordinate of the block.
	 * @param blockY The y-coordinate of the block.
	 * @param blockZ The z-coordinate of the block.
	 * @param state  The block state to be set.
	 */
	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state) {
		checkRaw();
		int sectionIndex = MCAUtil.blockToChunk(blockY);
		Section section = sections.get(sectionIndex);
		section.setBlockStateAt(blockX, blockY, blockZ, state);
	}

	/**
	 * @return The DataVersion of this chunk.
	 */
	public int getDataVersion() { return dataVersion; }

	/**
	 * Sets the DataVersion of this chunk. This does not check if the data of this chunk conforms
	 * to that DataVersion, that is the responsibility of the developer.
	 * 
	 * @param dataVersion The DataVersion to be set.
	 */
	public void setDataVersion(int dataVersion) {
		checkRaw();
		this.dataVersion = dataVersion;
	}

	/**
	 * @return The timestamp when this region file was last updated in seconds since 1970-01-01.
	 */
	public int getLastMCAUpdate() { return lastMCAUpdate; }

	/**
	 * Sets the timestamp when this region file was last updated in seconds since 1970-01-01.
	 * 
	 * @param lastMCAUpdate The time in seconds since 1970-01-01.
	 */
	public void setLastMCAUpdate(int lastMCAUpdate) {
		checkRaw();
		this.lastMCAUpdate = lastMCAUpdate;
	}

	/**
	 * @return The generation station of this chunk.
	 */
	public String getStatus() { return status; }

	/**
	 * Sets the generation status of this chunk.
	 * 
	 * @param status The generation status of this chunk.
	 */
	public void setStatus(String status) {
		checkRaw();
		this.status = status;
	}

	/**
	 * Fetches the section at the given y-coordinate.
	 * 
	 * @param sectionY The y-coordinate of the section in this chunk ranging from 0 to 15.
	 * @return The Section.
	 */
	public Section getSection(int sectionY) { return sections.get(sectionY); }

	/**
	 * Sets a section at a givesn y-coordinate
	 * 
	 * @param sectionY The y-coordinate of the section in this chunk ranging from 0 to 15.
	 * @param section  The section to be set.
	 */
	public void setSection(int sectionY, Section section) {
		checkRaw();
		sections.put(sectionY, section);
	}

	/**
	 * @return The timestamp when this chunk was last updated as a UNIX timestamp.
	 */
	public long getLastUpdate() { return lastUpdate; }

	/**
	 * Sets the time when this chunk was last updated as a UNIX timestamp.
	 * 
	 * @param lastUpdate The UNIX timestamp.
	 */
	public void setLastUpdate(long lastUpdate) {
		checkRaw();
		this.lastUpdate = lastUpdate;
	}

	/**
	 * @return The cumulative amount of time players have spent in this chunk in ticks.
	 */
	public long getInhabitedTime() { return inhabitedTime; }

	/**
	 * Sets the cumulative amount of time players have spent in this chunk in ticks.
	 * 
	 * @param inhabitedTime The time in ticks.
	 */
	public void setInhabitedTime(long inhabitedTime) {
		checkRaw();
		this.inhabitedTime = inhabitedTime;
	}


	/**
	 * @return The height maps of this chunk.
	 */
	public CompoundTag getHeightMaps() { return heightMaps; }

	/**
	 * Sets the height maps of this chunk.
	 * 
	 * @param heightMaps The height maps.
	 */
	public void setHeightMaps(CompoundTag heightMaps) {
		checkRaw();
		this.heightMaps = heightMaps;
	}

	/**
	 * @return The carving masks of this chunk.
	 */
	public CompoundTag getCarvingMasks() { return carvingMasks; }

	/**
	 * Sets the carving masks of this chunk.
	 * 
	 * @param carvingMasks The carving masks.
	 */
	public void setCarvingMasks(CompoundTag carvingMasks) {
		checkRaw();
		this.carvingMasks = carvingMasks;
	}

	/**
	 * @return The entities of this chunk.
	 */
	public ListTag<CompoundTag> getEntities() { return entities; }

	/**
	 * Sets the entities of this chunk.
	 * 
	 * @param entities The entities.
	 */
	public void setEntities(ListTag<CompoundTag> entities) {
		checkRaw();
		this.entities = entities;
	}

	/**
	 * @return The tile entities of this chunk.
	 */
	public ListTag<CompoundTag> getTileEntities() { return tileEntities; }

	/**
	 * Sets the tile entities of this chunk.
	 * 
	 * @param tileEntities The tile entities of this chunk.
	 */
	public void setTileEntities(ListTag<CompoundTag> tileEntities) {
		checkRaw();
		this.tileEntities = tileEntities;
	}

	/**
	 * @return The tile ticks of this chunk.
	 */
	public ListTag<CompoundTag> getTileTicks() { return tileTicks; }

	/**
	 * Sets the tile ticks of this chunk.
	 * 
	 * @param tileTicks Thee tile ticks.
	 */
	public void setTileTicks(ListTag<CompoundTag> tileTicks) {
		checkRaw();
		this.tileTicks = tileTicks;
	}

	/**
	 * @return The liquid ticks of this chunk.
	 */
	public ListTag<CompoundTag> getLiquidTicks() { return liquidTicks; }

	/**
	 * Sets the liquid ticks of this chunk.
	 * 
	 * @param liquidTicks The liquid ticks.
	 */
	public void setLiquidTicks(ListTag<CompoundTag> liquidTicks) {
		checkRaw();
		this.liquidTicks = liquidTicks;
	}

	/**
	 * @return The light sources in this chunk.
	 */
	public ListTag<ListTag<?>> getLights() { return lights; }

	/**
	 * Sets the light sources in this chunk.
	 * 
	 * @param lights The light sources.
	 */
	public void setLights(ListTag<ListTag<?>> lights) {
		checkRaw();
		this.lights = lights;
	}

	/**
	 * @return The liquids to be ticked in this chunk.
	 */
	public ListTag<ListTag<?>> getLiquidsToBeTicked() { return liquidsToBeTicked; }

	/**
	 * Sets the liquids to be ticked in this chunk.
	 * 
	 * @param liquidsToBeTicked The liquids to be ticked.
	 */
	public void setLiquidsToBeTicked(ListTag<ListTag<?>> liquidsToBeTicked) {
		checkRaw();
		this.liquidsToBeTicked = liquidsToBeTicked;
	}

	/**
	 * @return Stuff to be ticked in this chunk.
	 */
	public ListTag<ListTag<?>> getToBeTicked() { return toBeTicked; }

	/**
	 * Sets stuff to be ticked in this chunk.
	 * 
	 * @param toBeTicked The stuff to be ticked.
	 */
	public void setToBeTicked(ListTag<ListTag<?>> toBeTicked) {
		checkRaw();
		this.toBeTicked = toBeTicked;
	}


	public List<Section> getSections() { return this.sections.values().stream().sorted().toList(); }
	public Map<Integer, Section> getSectionMap() { return new HashMap<>(this.sections); }

	/**
	 * @return Things that are in post processing in this chunk.
	 */
	public ListTag<ListTag<?>> getPostProcessing() { return postProcessing; }

	/**
	 * Sets things to be post processed in this chunk.
	 * 
	 * @param postProcessing The things to be post processed.
	 */
	public void setPostProcessing(ListTag<ListTag<?>> postProcessing) {
		checkRaw();
		this.postProcessing = postProcessing;
	}

	/**
	 * @return Data about structures in this chunk.
	 */
	public CompoundTag getStructures() { return structures; }

	/**
	 * Sets data about structures in this chunk.
	 * 
	 * @param structures The data about structures.
	 */
	public void setStructures(CompoundTag structures) {
		checkRaw();
		this.structures = structures;
	}

	int getBlockIndex(int blockX, int blockZ) { return (blockZ & 0xF) * 16 + (blockX & 0xF); }

	public void cleanupPalettesAndBlockStates() { checkRaw(); }

	private void checkRaw() {
		if (raw) {
			throw new UnsupportedOperationException("cannot update field when working with raw data");
		}
	}

	public static Chunk newChunk() { return newChunk(DEFAULT_DATA_VERSION); }

	public static Chunk newChunk(int dataVersion) {
		Chunk c = new Chunk(0);
		c.dataVersion = dataVersion;
		c.data = new CompoundTag();
		c.data.put("Level", new CompoundTag());
		c.status = "mobs_spawned";
		return c;
	}

	/**
	 * Provides a reference to the full chunk data.
	 * 
	 * @return The full chunk data or null if there is none, e.g. when this chunk has only been loaded partially.
	 */
	public CompoundTag getHandle() { return data; }

	public CompoundTag updateHandle(int xPos, int zPos) {
		if (raw) {
			return data;
		}

		data.putInt("DataVersion", dataVersion);
		CompoundTag level = data.getCompoundTag("Level");
		level.putInt("xPos", xPos);
		level.putInt("zPos", zPos);
		level.putLong("LastUpdate", lastUpdate);
		level.putLong("InhabitedTime", inhabitedTime);
		if (heightMaps != null) {
			level.put("Heightmaps", heightMaps);
		}
		if (carvingMasks != null) {
			level.put("CarvingMasks", carvingMasks);
		}
		if (entities != null) {
			level.put("Entities", entities);
		}
		if (tileEntities != null) {
			level.put("TileEntities", tileEntities);
		}
		if (tileTicks != null) {
			level.put("TileTicks", tileTicks);
		}
		if (liquidTicks != null) {
			level.put("LiquidTicks", liquidTicks);
		}
		if (lights != null) {
			level.put("Lights", lights);
		}
		if (liquidsToBeTicked != null) {
			level.put("LiquidsToBeTicked", liquidsToBeTicked);
		}
		if (toBeTicked != null) {
			level.put("ToBeTicked", toBeTicked);
		}
		if (postProcessing != null) {
			level.put("PostProcessing", postProcessing);
		}
		level.putString("Status", status);
		if (structures != null) {
			level.put("Structures", structures);
		}
		ListTag<CompoundTag> sections = new ListTag<>(CompoundTag.class);
		for (Section section : this.sections.values()) {
			if (section != null) {
				sections.add(section.getSource());
			}
		}
		level.put("Sections", sections);
		return data;
	}

	public List<LocatedTag<CompoundTag>> locationsOf(Predicate<CompoundTag> checker) {
		return this.sections.values().stream().flatMap(
				s -> s.getBlockLocations(checker).stream().map(l -> new LocatedTag<>(l.x(), l.y() + (s.getHeight() * 16), l.z(), l.tag())))
				.toList();
	}
	public List<LocatedTag<CompoundTag>> locationsOf(String blockName) {
		return this.sections.values().stream().flatMap(s -> s.getBlockLocations(blockName).stream()
				.map(l -> new LocatedTag<>(l.x(), l.y() + (s.getHeight() * 16), l.z(), l.tag()))).toList();
	}

	@Override
	public Iterator<Section> iterator() { return sections.values().iterator(); }

	@Override
	public String toString() {
		return "Chunk{" + "xPos=" + xPos + ", zPos=" + zPos + ", lastMCAUpdate=" + lastMCAUpdate + ", dataVersion=" + dataVersion
				+ ", lastUpdate=" + lastUpdate + ", inhabitedTime=" + inhabitedTime + ", heightMaps=" + heightMaps + ", carvingMasks="
				+ carvingMasks + ", sections=" + sections + ", entities=" + entities + ", tileEntities=" + tileEntities + ", tileTicks="
				+ tileTicks + ", liquidTicks=" + liquidTicks + ", lights=" + lights + ", liquidsToBeTicked=" + liquidsToBeTicked
				+ ", toBeTicked=" + toBeTicked + ", postProcessing=" + postProcessing + ", status='" + status + '\'' + ", structures="
				+ structures + "blockEntities=" + blockEntities + "}\n Data:" + data + '\n';
	}
}
