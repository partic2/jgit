/*
 * Copyright (C) 2022, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.patch;

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.InflaterInputStream;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.errors.FilterFailedException;
import org.eclipse.jgit.api.errors.PatchApplyException;
import org.eclipse.jgit.api.errors.PatchFormatException;
import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.attributes.Attributes;
import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheCheckout.CheckoutMetadata;
import org.eclipse.jgit.dircache.DirCacheCheckout.StreamSupplier;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.IndexWriteException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.FS.ExecutionResult;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.LfsFactory;
import org.eclipse.jgit.util.LfsFactory.LfsInputStream;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.TemporaryBuffer.LocalFile;
import org.eclipse.jgit.util.io.BinaryDeltaInputStream;
import org.eclipse.jgit.util.io.BinaryHunkInputStream;
import org.eclipse.jgit.util.io.EolStreamTypeUtil;
import org.eclipse.jgit.util.sha1.SHA1;

/**
 * Applies a patch to files and the index.
 * <p>
 * After instantiating, applyPatch() should be called once.
 * </p>
 *
 * @since 6.4
 */
public class PatchApplier {

	/** The tree before applying the patch. Only non-null for inCore operation. */
	@Nullable
	private final RevTree beforeTree;

	private final Repository repo;

	private final ObjectInserter inserter;

	private final ObjectReader reader;

	private WorkingTreeOptions workingTreeOptions;

	private int inCoreSizeLimit;

	/**
	 * @param repo
	 *            repository to apply the patch in
	 */
	public PatchApplier(Repository repo) {
		this.repo = repo;
		inserter = repo.newObjectInserter();
		reader = inserter.newReader();
		beforeTree = null;

		Config config = repo.getConfig();
		workingTreeOptions = config.get(WorkingTreeOptions.KEY);
		inCoreSizeLimit = config.getInt(ConfigConstants.CONFIG_MERGE_SECTION,
				ConfigConstants.CONFIG_KEY_IN_CORE_LIMIT, 10 << 20);
	}

	/**
	 * @param repo
	 *            repository to apply the patch in
	 * @param beforeTree
	 *            ID of the tree to apply the patch in
	 * @param oi
	 *            to be used for modifying objects
	 * @throws IOException
	 *             in case of I/O errors
	 */
	public PatchApplier(Repository repo, RevTree beforeTree, ObjectInserter oi)
			throws IOException {
		this.repo = repo;
		this.beforeTree = beforeTree;
		inserter = oi;
		reader = oi.newReader();
	}

	/**
	 * A wrapper for returning both the applied tree ID and the applied files
	 * list.
	 *
	 * @since 6.3
	 */
	public static class Result {

		private ObjectId treeId;

		private List<String> paths;

		/**
		 * @return List of modified paths.
		 */
		public List<String> getPaths() {
			return paths;
		}

		/**
		 * @return The applied tree ID.
		 */
		public ObjectId getTreeId() {
			return treeId;
		}
	}

	/**
	 * Applies the given patch
	 *
	 * @param patchInput
	 *            the patch to apply.
	 * @return the result of the patch
	 * @throws PatchFormatException
	 *             if the patch cannot be parsed
	 * @throws PatchApplyException
	 *             if the patch cannot be applied
	 */
	public Result applyPatch(InputStream patchInput)
			throws PatchFormatException, PatchApplyException {
		Result result = new Result();
		org.eclipse.jgit.patch.Patch p = new org.eclipse.jgit.patch.Patch();
		try (InputStream inStream = patchInput) {
			p.parse(inStream);

			if (!p.getErrors().isEmpty()) {
				throw new PatchFormatException(p.getErrors());
			}

			DirCache dirCache = (inCore()) ? DirCache.newInCore()
					: repo.lockDirCache();

			DirCacheBuilder dirCacheBuilder = dirCache.builder();
			Set<String> modifiedPaths = new HashSet<>();
			for (org.eclipse.jgit.patch.FileHeader fh : p.getFiles()) {
				ChangeType type = fh.getChangeType();
				switch (type) {
				case ADD: {
					File f = getFile(fh.getNewPath());
					if (f != null) {
						try {
							FileUtils.mkdirs(f.getParentFile(), true);
							FileUtils.createNewFile(f);
						} catch (IOException e) {
							throw new PatchApplyException(MessageFormat.format(
									JGitText.get().createNewFileFailed, f), e);
						}
					}
					apply(fh.getNewPath(), dirCache, dirCacheBuilder, f, fh);
				}
					break;
				case MODIFY:
					apply(fh.getOldPath(), dirCache, dirCacheBuilder,
							getFile(fh.getOldPath()), fh);
					break;
				case DELETE:
					if (!inCore()) {
						File old = getFile(fh.getOldPath());
						if (!old.delete())
							throw new PatchApplyException(MessageFormat.format(
									JGitText.get().cannotDeleteFile, old));
					}
					break;
				case RENAME: {
					File src = getFile(fh.getOldPath());
					File dest = getFile(fh.getNewPath());

					if (!inCore()) {
						/*
						 * this is odd: we rename the file on the FS, but
						 * apply() will write a fresh stream anyway, which will
						 * overwrite if there were hunks in the patch.
						 */
						try {
							FileUtils.mkdirs(dest.getParentFile(), true);
							FileUtils.rename(src, dest,
									StandardCopyOption.ATOMIC_MOVE);
						} catch (IOException e) {
							throw new PatchApplyException(MessageFormat.format(
									JGitText.get().renameFileFailed, src, dest),
									e);
						}
					}
					String pathWithOriginalContent = inCore() ?
							fh.getOldPath() : fh.getNewPath();
					apply(pathWithOriginalContent, dirCache, dirCacheBuilder, dest, fh);
					break;
				}
				case COPY: {
					File dest = getFile(fh.getNewPath());
					if (!inCore()) {
						File src = getFile(fh.getOldPath());
						FileUtils.mkdirs(dest.getParentFile(), true);
						Files.copy(src.toPath(), dest.toPath());
					}
					apply(fh.getOldPath(), dirCache, dirCacheBuilder, dest, fh);
					break;
				}
				}
				if (fh.getChangeType() != ChangeType.DELETE)
					modifiedPaths.add(fh.getNewPath());
				if (fh.getChangeType() != ChangeType.COPY
						&& fh.getChangeType() != ChangeType.ADD)
					modifiedPaths.add(fh.getOldPath());
			}

			// We processed the patch. Now add things that weren't changed.
			for (int i = 0; i < dirCache.getEntryCount(); i++) {
				DirCacheEntry dce = dirCache.getEntry(i);
				if (!modifiedPaths.contains(dce.getPathString())
						|| dce.getStage() != DirCacheEntry.STAGE_0)
					dirCacheBuilder.add(dce);
			}

			if (inCore())
				dirCacheBuilder.finish();
			else if (!dirCacheBuilder.commit()) {
				throw new IndexWriteException();
			}

			result.treeId = dirCache.writeTree(inserter);
			result.paths = modifiedPaths.stream().sorted()
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new PatchApplyException(MessageFormat.format(
					JGitText.get().patchApplyException, e.getMessage()), e);
		}
		return result;
	}

	private File getFile(String path) {
		return (inCore()) ? null : new File(repo.getWorkTree(), path);
	}

	/* returns null if the path is not found. */
	@Nullable
	private TreeWalk getTreeWalkForFile(String path, DirCache cache)
			throws PatchApplyException {
		try {
			if (inCore()) {
				// Only this branch may return null.
				// TODO: it would be nice if we could return a TreeWalk at EOF
				// iso. null.
				return TreeWalk.forPath(repo, path, beforeTree);
			}
			TreeWalk walk = new TreeWalk(repo);

			// Use a TreeWalk with a DirCacheIterator to pick up the correct
			// clean/smudge filters.
			int cacheTreeIdx = walk.addTree(new DirCacheIterator(cache));
			FileTreeIterator files = new FileTreeIterator(repo);
			if (FILE_TREE_INDEX != walk.addTree(files))
				throw new IllegalStateException();

			walk.setFilter(AndTreeFilter.create(
					PathFilterGroup.createFromStrings(path),
					new NotIgnoredFilter(FILE_TREE_INDEX)));
			walk.setOperationType(OperationType.CHECKIN_OP);
			walk.setRecursive(true);
			files.setDirCacheIterator(walk, cacheTreeIdx);
			return walk;
		} catch (IOException e) {
			throw new PatchApplyException(MessageFormat.format(
					JGitText.get().patchApplyException, e.getMessage()), e);
		}
	}

	private static final int FILE_TREE_INDEX = 1;

	/**
	 * Applies patch to a single file.
	 *
	 * @param pathWithOriginalContent
	 *            The path to use for the pre-image. Also determines CRLF and
	 *            smudge settings.
	 * @param dirCache
	 *            Dircache to read existing data from.
	 * @param dirCacheBuilder
	 *            Builder for Dircache to write new data to.
	 * @param f
	 *            The file to update with new contents. Null for inCore usage.
	 * @param fh
	 *            The patch header.
	 * @throws PatchApplyException
	 */
	private void apply(String pathWithOriginalContent, DirCache dirCache,
			DirCacheBuilder dirCacheBuilder, @Nullable File f,
			org.eclipse.jgit.patch.FileHeader fh) throws PatchApplyException {
		if (PatchType.BINARY.equals(fh.getPatchType())) {
			// This patch type just says "something changed". We can't do
			// anything with that.
			// Maybe this should return an error code, though?
			return;
		}
		try {
			TreeWalk walk = getTreeWalkForFile(pathWithOriginalContent, dirCache);
			boolean loadedFromTreeWalk = false;
			// CR-LF handling is determined by whether the file or the patch
			// have CR-LF line endings.
			boolean convertCrLf = inCore() || needsCrLfConversion(f, fh);
			EolStreamType streamType = convertCrLf ? EolStreamType.TEXT_CRLF
					: EolStreamType.DIRECT;
			String smudgeFilterCommand = null;
			StreamSupplier fileStreamSupplier = null;
			ObjectId fileId = ObjectId.zeroId();
			if (walk == null) {
				// For new files with inCore()==true, TreeWalk.forPath can be
				// null. Stay with defaults.
			} else if (inCore()) {
				fileId = walk.getObjectId(0);
				ObjectLoader loader = LfsFactory.getInstance()
						.applySmudgeFilter(repo, reader.open(fileId, OBJ_BLOB),
								null);
				byte[] data = loader.getBytes();
				convertCrLf = RawText.isCrLfText(data);
				fileStreamSupplier = () -> new ByteArrayInputStream(data);
				streamType = convertCrLf ? EolStreamType.TEXT_CRLF
						: EolStreamType.DIRECT;
				smudgeFilterCommand = walk
						.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE);
				loadedFromTreeWalk = true;
			} else if (walk.next()) {
				// If the file on disk has no newline characters,
				// convertCrLf will be false. In that case we want to honor the
				// normal git settings.
				streamType = convertCrLf ? EolStreamType.TEXT_CRLF
						: walk.getEolStreamType(OperationType.CHECKOUT_OP);
				smudgeFilterCommand = walk
						.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE);
				FileTreeIterator file = walk.getTree(FILE_TREE_INDEX,
						FileTreeIterator.class);
				if (file != null) {
					fileId = file.getEntryObjectId();
					fileStreamSupplier = file::openEntryStream;
					loadedFromTreeWalk = true;
				} else {
					throw new PatchApplyException(MessageFormat.format(
							JGitText.get().cannotReadFile,
							pathWithOriginalContent));
				}
			}

			if (fileStreamSupplier == null)
				fileStreamSupplier = inCore() ? InputStream::nullInputStream
						: () -> new FileInputStream(f);

			FileMode fileMode = fh.getNewMode() != null ? fh.getNewMode()
					: FileMode.REGULAR_FILE;
			ContentStreamLoader resultStreamLoader;
			if (PatchType.GIT_BINARY.equals(fh.getPatchType())) {
				// binary patches are processed in a streaming fashion. Some
				// binary patches do random access on the input data, so we can't
				// overwrite the file while we're streaming.
				resultStreamLoader = applyBinary(pathWithOriginalContent, f, fh,
						fileStreamSupplier, fileId);
			} else {
				String filterCommand = walk != null
						? walk.getFilterCommand(
								Constants.ATTR_FILTER_TYPE_CLEAN)
						: null;
				RawText raw = getRawText(f, fileStreamSupplier, fileId,
						pathWithOriginalContent, loadedFromTreeWalk, filterCommand,
						convertCrLf);
				resultStreamLoader = applyText(raw, fh);
			}

			if (f != null) {
				// Write to a buffer and copy to the file only if everything was
				// fine.
				TemporaryBuffer buffer = new TemporaryBuffer.LocalFile(null);
				try {
					CheckoutMetadata metadata = new CheckoutMetadata(streamType,
							smudgeFilterCommand);

					try (TemporaryBuffer buf = buffer) {
						DirCacheCheckout.getContent(repo, pathWithOriginalContent,
								metadata, resultStreamLoader.supplier, workingTreeOptions,
								buf);
					}
					try (InputStream bufIn = buffer.openInputStream()) {
						Files.copy(bufIn, f.toPath(),
								StandardCopyOption.REPLACE_EXISTING);
					}
				} finally {
					buffer.destroy();
				}

				repo.getFS().setExecute(f,
						fileMode == FileMode.EXECUTABLE_FILE);
			}

			Instant lastModified = f == null ? null
					: repo.getFS().lastModifiedInstant(f);
			Attributes attributes = walk != null ? walk.getAttributes()
					: new Attributes();

			DirCacheEntry dce = insertToIndex(
					resultStreamLoader.supplier.load(),
					fh.getNewPath().getBytes(StandardCharsets.UTF_8), fileMode,
					lastModified, resultStreamLoader.length,
					attributes.get(Constants.ATTR_FILTER));
			dirCacheBuilder.add(dce);
			if (PatchType.GIT_BINARY.equals(fh.getPatchType())
					&& fh.getNewId() != null && fh.getNewId().isComplete()
					&& !fh.getNewId().toObjectId().equals(dce.getObjectId())) {
				throw new PatchApplyException(MessageFormat.format(
						JGitText.get().applyBinaryResultOidWrong,
						pathWithOriginalContent));
			}
		} catch (IOException | UnsupportedOperationException e) {
			throw new PatchApplyException(MessageFormat.format(
					JGitText.get().patchApplyException, e.getMessage()), e);
		}
	}

	private DirCacheEntry insertToIndex(InputStream input, byte[] path,
			FileMode fileMode, Instant lastModified, long length,
			Attribute lfsAttribute) throws IOException {
		DirCacheEntry dce = new DirCacheEntry(path, DirCacheEntry.STAGE_0);
		dce.setFileMode(fileMode);
		if (lastModified != null) {
			dce.setLastModified(lastModified);
		}
		dce.setLength(length);

		try (LfsInputStream is = org.eclipse.jgit.util.LfsFactory.getInstance()
				.applyCleanFilter(repo, input, length, lfsAttribute)) {
			dce.setObjectId(inserter.insert(OBJ_BLOB, is.getLength(), is));
		}

		return dce;
	}

	/**
	 * Gets the raw text of the given file.
	 *
	 * @param file
	 *            to read from
	 * @param fileStreamSupplier
	 *            if fromTreewalk, the stream of the file content
	 * @param fileId
	 *            of the file
	 * @param path
	 *            of the file
	 * @param fromTreeWalk
	 *            whether the file was loaded by a {@link TreeWalk}
	 * @param filterCommand
	 *            for reading the file content
	 * @param convertCrLf
	 *            whether a CR-LF conversion is needed
	 * @return the result raw text
	 * @throws IOException
	 *             in case of filtering issues
	 */
	private RawText getRawText(@Nullable File file,
			StreamSupplier fileStreamSupplier, ObjectId fileId, String path,
			boolean fromTreeWalk, String filterCommand, boolean convertCrLf)
			throws IOException {
		if (fromTreeWalk) {
			// Can't use file.openEntryStream() as we cannot control its CR-LF
			// conversion.
			try (InputStream input = filterClean(repo, path,
					fileStreamSupplier.load(), convertCrLf, filterCommand)) {
				return new RawText(org.eclipse.jgit.util.IO
						.readWholeStream(input, 0).array());
			}
		}
		if (convertCrLf) {
			try (InputStream input = EolStreamTypeUtil.wrapInputStream(
					fileStreamSupplier.load(), EolStreamType.TEXT_LF)) {
				return new RawText(org.eclipse.jgit.util.IO
						.readWholeStream(input, 0).array());
			}
		}
		if (inCore() && fileId.equals(ObjectId.zeroId())) {
			return new RawText(new byte[] {});
		}
		return new RawText(file);
	}

	private InputStream filterClean(Repository repository, String path,
			InputStream fromFile, boolean convertCrLf, String filterCommand)
			throws IOException {
		InputStream input = fromFile;
		if (convertCrLf) {
			input = EolStreamTypeUtil.wrapInputStream(input,
					EolStreamType.TEXT_LF);
		}
		if (org.eclipse.jgit.util.StringUtils.isEmptyOrNull(filterCommand)) {
			return input;
		}
		if (FilterCommandRegistry.isRegistered(filterCommand)) {
			LocalFile buffer = new org.eclipse.jgit.util.TemporaryBuffer.LocalFile(
					null, inCoreSizeLimit);
			FilterCommand command = FilterCommandRegistry.createFilterCommand(
					filterCommand, repository, input, buffer);
			while (command.run() != -1) {
				// loop as long as command.run() tells there is work to do
			}
			return buffer.openInputStreamWithAutoDestroy();
		}
		org.eclipse.jgit.util.FS fs = repository.getFS();
		ProcessBuilder filterProcessBuilder = fs.runInShell(filterCommand,
				new String[0]);
		filterProcessBuilder.directory(repository.getWorkTree());
		filterProcessBuilder.environment().put(Constants.GIT_DIR_KEY,
				repository.getDirectory().getAbsolutePath());
		ExecutionResult result;
		try {
			result = fs.execute(filterProcessBuilder, input);
		} catch (IOException | InterruptedException e) {
			throw new IOException(
					new FilterFailedException(e, filterCommand, path));
		}
		int rc = result.getRc();
		if (rc != 0) {
			throw new IOException(new FilterFailedException(rc, filterCommand,
					path, result.getStdout().toByteArray(4096),
					org.eclipse.jgit.util.RawParseUtils
							.decode(result.getStderr().toByteArray(4096))));
		}
		return result.getStdout().openInputStreamWithAutoDestroy();
	}

	private boolean needsCrLfConversion(File f,
			org.eclipse.jgit.patch.FileHeader fileHeader) throws IOException {
		if (PatchType.GIT_BINARY.equals(fileHeader.getPatchType())) {
			return false;
		}
		if (!hasCrLf(fileHeader)) {
			try (InputStream input = new FileInputStream(f)) {
				return RawText.isCrLfText(input);
			}
		}
		return false;
	}

	private static boolean hasCrLf(
			org.eclipse.jgit.patch.FileHeader fileHeader) {
		if (PatchType.GIT_BINARY.equals(fileHeader.getPatchType())) {
			return false;
		}
		for (org.eclipse.jgit.patch.HunkHeader header : fileHeader.getHunks()) {
			byte[] buf = header.getBuffer();
			int hunkEnd = header.getEndOffset();
			int lineStart = header.getStartOffset();
			while (lineStart < hunkEnd) {
				int nextLineStart = RawParseUtils.nextLF(buf, lineStart);
				if (nextLineStart > hunkEnd) {
					nextLineStart = hunkEnd;
				}
				if (nextLineStart <= lineStart) {
					break;
				}
				if (nextLineStart - lineStart > 1) {
					char first = (char) (buf[lineStart] & 0xFF);
					if (first == ' ' || first == '-') {
						// It's an old line. Does it end in CR-LF?
						if (buf[nextLineStart - 2] == '\r') {
							return true;
						}
					}
				}
				lineStart = nextLineStart;
			}
		}
		return false;
	}

	private ObjectId hash(File f) throws IOException {
		try (FileInputStream fis = new FileInputStream(f);
				SHA1InputStream shaStream = new SHA1InputStream(fis,
						f.length())) {
			shaStream.transferTo(OutputStream.nullOutputStream());
			return shaStream.getHash().toObjectId();
		}
	}

	private void checkOid(ObjectId baseId, ObjectId id, ChangeType type, File f,
			String path) throws PatchApplyException, IOException {
		boolean hashOk = false;
		if (id != null) {
			hashOk = baseId.equals(id);
			if (!hashOk && ChangeType.ADD.equals(type)
					&& ObjectId.zeroId().equals(baseId)) {
				// We create a new file. The OID of an empty file is not the
				// zero id!
				hashOk = Constants.EMPTY_BLOB_ID.equals(id);
			}
		} else if (!inCore()) {
			if (ObjectId.zeroId().equals(baseId)) {
				// File empty is OK.
				hashOk = !f.exists() || f.length() == 0;
			} else {
				hashOk = baseId.equals(hash(f));
			}
		}
		if (!hashOk) {
			throw new PatchApplyException(MessageFormat
					.format(JGitText.get().applyBinaryBaseOidWrong, path));
		}
	}

	private boolean inCore() {
		return beforeTree != null;
	}

	/**
	 * Provide stream, along with the length of the object. We use this once to
	 * patch to the working tree, once to write the index. For on-disk
	 * operation, presumably we could stream to the destination file, and then
	 * read back the stream from disk. We don't because it is more complex.
	 */
	private static class ContentStreamLoader {

		StreamSupplier supplier;

		long length;

		ContentStreamLoader(StreamSupplier supplier, long length) {
			this.supplier = supplier;
			this.length = length;
		}
	}

	/**
	 * Applies a binary patch.
	 *
	 * @param path
	 *            pathname of the file to write.
	 * @param f
	 *            destination file
	 * @param fh
	 *            the patch to apply
	 * @param inputSupplier
	 *            a supplier for the contents of the old file
	 * @param id
	 *            SHA1 for the old content
	 * @return a loader for the new content.
	 * @throws PatchApplyException
	 * @throws IOException
	 * @throws UnsupportedOperationException
	 */
	private ContentStreamLoader applyBinary(String path, File f,
			org.eclipse.jgit.patch.FileHeader fh, StreamSupplier inputSupplier,
			ObjectId id) throws PatchApplyException, IOException,
			UnsupportedOperationException {
		if (!fh.getOldId().isComplete() || !fh.getNewId().isComplete()) {
			throw new PatchApplyException(MessageFormat
					.format(JGitText.get().applyBinaryOidTooShort, path));
		}
		org.eclipse.jgit.patch.BinaryHunk hunk = fh.getForwardBinaryHunk();
		// A BinaryHunk has the start at the "literal" or "delta" token. Data
		// starts on the next line.
		int start = RawParseUtils.nextLF(hunk.getBuffer(),
				hunk.getStartOffset());
		int length = hunk.getEndOffset() - start;
		switch (hunk.getType()) {
		case LITERAL_DEFLATED: {
			// This just overwrites the file. We need to check the hash of
			// the base.
			checkOid(fh.getOldId().toObjectId(), id, fh.getChangeType(), f,
					path);
			StreamSupplier supp = () -> new InflaterInputStream(
					new BinaryHunkInputStream(new ByteArrayInputStream(
							hunk.getBuffer(), start, length)));
			return new ContentStreamLoader(supp, hunk.getSize());
		}
		case DELTA_DEFLATED: {
			// Unfortunately delta application needs random access to the
			// base to construct the result.
			byte[] base;
			try (InputStream in = inputSupplier.load()) {
				base = IO.readWholeStream(in, 0).array();
			}
			// At least stream the result! We don't have to close these streams,
			// as they don't hold resources.
			StreamSupplier supp = () -> new BinaryDeltaInputStream(base,
					new InflaterInputStream(
							new BinaryHunkInputStream(new ByteArrayInputStream(
									hunk.getBuffer(), start, length))));

			// This just reads the first bits of the stream.
			long finalSize = ((BinaryDeltaInputStream) supp.load()).getExpectedResultSize();

			return new ContentStreamLoader(supp, finalSize);
		}
		default:
			throw new UnsupportedOperationException(MessageFormat.format(
					JGitText.get().applyBinaryPatchTypeNotSupported,
					hunk.getType().name()));
		}
	}

	private ContentStreamLoader applyText(RawText rt,
			org.eclipse.jgit.patch.FileHeader fh)
			throws IOException, PatchApplyException {
		List<ByteBuffer> oldLines = new ArrayList<>(rt.size());
		for (int i = 0; i < rt.size(); i++) {
			oldLines.add(rt.getRawString(i));
		}
		List<ByteBuffer> newLines = new ArrayList<>(oldLines);
		int afterLastHunk = 0;
		int lineNumberShift = 0;
		int lastHunkNewLine = -1;
		for (org.eclipse.jgit.patch.HunkHeader hh : fh.getHunks()) {
			// We assume hunks to be ordered
			if (hh.getNewStartLine() <= lastHunkNewLine) {
				throw new PatchApplyException(MessageFormat
						.format(JGitText.get().patchApplyException, hh));
			}
			lastHunkNewLine = hh.getNewStartLine();

			byte[] b = new byte[hh.getEndOffset() - hh.getStartOffset()];
			System.arraycopy(hh.getBuffer(), hh.getStartOffset(), b, 0,
					b.length);
			RawText hrt = new RawText(b);

			List<ByteBuffer> hunkLines = new ArrayList<>(hrt.size());
			for (int i = 0; i < hrt.size(); i++) {
				hunkLines.add(hrt.getRawString(i));
			}

			if (hh.getNewStartLine() == 0) {
				// Must be the single hunk for clearing all content
				if (fh.getHunks().size() == 1
						&& canApplyAt(hunkLines, newLines, 0)) {
					newLines.clear();
					break;
				}
				throw new PatchApplyException(MessageFormat
						.format(JGitText.get().patchApplyException, hh));
			}
			// Hunk lines as reported by the hunk may be off, so don't rely on
			// them.
			int applyAt = hh.getNewStartLine() - 1 + lineNumberShift;
			// But they definitely should not go backwards.
			if (applyAt < afterLastHunk && lineNumberShift < 0) {
				applyAt = hh.getNewStartLine() - 1;
				lineNumberShift = 0;
			}
			if (applyAt < afterLastHunk) {
				throw new PatchApplyException(MessageFormat
						.format(JGitText.get().patchApplyException, hh));
			}
			boolean applies = false;
			int oldLinesInHunk = hh.getLinesContext()
					+ hh.getOldImage().getLinesDeleted();
			if (oldLinesInHunk <= 1) {
				// Don't shift hunks without context lines. Just try the
				// position corrected by the current lineNumberShift, and if
				// that fails, the position recorded in the hunk header.
				applies = canApplyAt(hunkLines, newLines, applyAt);
				if (!applies && lineNumberShift != 0) {
					applyAt = hh.getNewStartLine() - 1;
					applies = applyAt >= afterLastHunk
							&& canApplyAt(hunkLines, newLines, applyAt);
				}
			} else {
				int maxShift = applyAt - afterLastHunk;
				for (int shift = 0; shift <= maxShift; shift++) {
					if (canApplyAt(hunkLines, newLines, applyAt - shift)) {
						applies = true;
						applyAt -= shift;
						break;
					}
				}
				if (!applies) {
					// Try shifting the hunk downwards
					applyAt = hh.getNewStartLine() - 1 + lineNumberShift;
					maxShift = newLines.size() - applyAt - oldLinesInHunk;
					for (int shift = 1; shift <= maxShift; shift++) {
						if (canApplyAt(hunkLines, newLines, applyAt + shift)) {
							applies = true;
							applyAt += shift;
							break;
						}
					}
				}
			}
			if (!applies) {
				throw new PatchApplyException(MessageFormat
						.format(JGitText.get().patchApplyException, hh));
			}
			// Hunk applies at applyAt. Apply it, and update afterLastHunk and
			// lineNumberShift
			lineNumberShift = applyAt - hh.getNewStartLine() + 1;
			int sz = hunkLines.size();
			for (int j = 1; j < sz; j++) {
				ByteBuffer hunkLine = hunkLines.get(j);
				if (!hunkLine.hasRemaining()) {
					// Completely empty line; accept as empty context line
					applyAt++;
					continue;
				}
				switch (hunkLine.array()[hunkLine.position()]) {
				case ' ':
					applyAt++;
					break;
				case '-':
					newLines.remove(applyAt);
					break;
				case '+':
					newLines.add(applyAt++, slice(hunkLine, 1));
					break;
				default:
					break;
				}
			}
			afterLastHunk = applyAt;
		}
		if (!isNoNewlineAtEndOfFile(fh)) {
			newLines.add(null);
		}
		if (!rt.isMissingNewlineAtEnd()) {
			oldLines.add(null);
		}

		// We could check if old == new, but the short-circuiting complicates
		// logic for inCore patching, so just write the new thing regardless.
		TemporaryBuffer buffer = new TemporaryBuffer.LocalFile(null);
		try (OutputStream out = buffer) {
			for (Iterator<ByteBuffer> l = newLines.iterator(); l.hasNext();) {
				ByteBuffer line = l.next();
				if (line == null) {
					// Must be the marker for the final newline
					break;
				}
				out.write(line.array(), line.position(), line.remaining());
				if (l.hasNext()) {
					out.write('\n');
				}
			}
			return new ContentStreamLoader(buffer::openInputStream,
					buffer.length());
		}
	}

	private boolean canApplyAt(List<ByteBuffer> hunkLines,
			List<ByteBuffer> newLines, int line) {
		int sz = hunkLines.size();
		int limit = newLines.size();
		int pos = line;
		for (int j = 1; j < sz; j++) {
			ByteBuffer hunkLine = hunkLines.get(j);
			if (!hunkLine.hasRemaining()) {
				// Empty line. Accept as empty context line.
				if (pos >= limit || newLines.get(pos).hasRemaining()) {
					return false;
				}
				pos++;
				continue;
			}
			switch (hunkLine.array()[hunkLine.position()]) {
			case ' ':
			case '-':
				if (pos >= limit
						|| !newLines.get(pos).equals(slice(hunkLine, 1))) {
					return false;
				}
				pos++;
				break;
			default:
				break;
			}
		}
		return true;
	}

	private ByteBuffer slice(ByteBuffer b, int off) {
		int newOffset = b.position() + off;
		return ByteBuffer.wrap(b.array(), newOffset, b.limit() - newOffset);
	}

	private boolean isNoNewlineAtEndOfFile(
			org.eclipse.jgit.patch.FileHeader fh) {
		List<? extends org.eclipse.jgit.patch.HunkHeader> hunks = fh.getHunks();
		if (hunks == null || hunks.isEmpty()) {
			return false;
		}
		org.eclipse.jgit.patch.HunkHeader lastHunk = hunks
				.get(hunks.size() - 1);
		byte[] buf = new byte[lastHunk.getEndOffset()
				- lastHunk.getStartOffset()];
		System.arraycopy(lastHunk.getBuffer(), lastHunk.getStartOffset(), buf,
				0, buf.length);
		RawText lhrt = new RawText(buf);
		return lhrt.getString(lhrt.size() - 1)
				.equals("\\ No newline at end of file"); //$NON-NLS-1$
	}

	/**
	 * An {@link InputStream} that updates a {@link SHA1} on every byte read.
	 */
	private static class SHA1InputStream extends InputStream {

		private final SHA1 hash;

		private final InputStream in;

		SHA1InputStream(InputStream in, long size) {
			hash = SHA1.newInstance();
			hash.update(Constants.encodedTypeString(Constants.OBJ_BLOB));
			hash.update((byte) ' ');
			hash.update(Constants.encodeASCII(size));
			hash.update((byte) 0);
			this.in = in;
		}

		public SHA1 getHash() {
			return hash;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) {
				hash.update((byte) b);
			}
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = in.read(b, off, len);
			if (n > 0) {
				hash.update(b, off, n);
			}
			return n;
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
