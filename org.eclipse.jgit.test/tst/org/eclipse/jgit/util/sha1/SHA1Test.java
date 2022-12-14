/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.sha1;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.sha1.SHA1.Sha1Implementation;
import org.junit.After;
import org.junit.Before;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class SHA1Test {
	private static final String TEST1 = "abc";

	private static final String TEST2a = "abcdbcdecdefdefgefghfghighijhi";
	private static final String TEST2b = "jkijkljklmklmnlmnomnopnopq";
	private static final String TEST2 = TEST2a + TEST2b;

	@DataPoints
	public static Sha1Implementation[] getDataPoints() {
		return new Sha1Implementation[] { Sha1Implementation.JAVA,
				Sha1Implementation.JDKNATIVE };
	}

	private Sha1Implementation sha1Implementation;

	public SHA1Test(Sha1Implementation impl) {
		this.sha1Implementation = impl;
	}

	@Before
	public void setUp() throws Exception {
		MockSystemReader mockSystemReader = new MockSystemReader();
		SystemReader.setInstance(mockSystemReader);
			System.setProperty("org.eclipse.jgit.util.sha1.implementation",
					sha1Implementation.name());
	}

	@After
	public void tearDown() {
		SystemReader.setInstance(null);
	}

	@Theory
	public void test0() throws NoSuchAlgorithmException {
		ObjectId exp = ObjectId
				.fromString("da39a3ee5e6b4b0d3255bfef95601890afd80709");

		MessageDigest m = MessageDigest.getInstance("SHA-1");
		m.update(new byte[] {});
		ObjectId m1 = ObjectId.fromRaw(m.digest());

		SHA1 s = SHA1.newInstance();
		s.update(new byte[] {});
		ObjectId s1 = ObjectId.fromRaw(s.digest());

		s.reset();
		s.update(new byte[] {});
		ObjectId s2 = s.toObjectId();

		assertEquals(m1, s1);
		assertEquals(exp, s1);
		assertEquals(exp, s2);
	}

	@Theory
	public void test1() throws NoSuchAlgorithmException {
		ObjectId exp = ObjectId
				.fromString("a9993e364706816aba3e25717850c26c9cd0d89d");

		MessageDigest m = MessageDigest.getInstance("SHA-1");
		m.update(TEST1.getBytes(UTF_8));
		ObjectId m1 = ObjectId.fromRaw(m.digest());

		SHA1 s = SHA1.newInstance();
		s.update(TEST1.getBytes(UTF_8));
		ObjectId s1 = ObjectId.fromRaw(s.digest());

		s.reset();
		s.update(TEST1.getBytes(UTF_8));
		ObjectId s2 = s.toObjectId();

		assertEquals(m1, s1);
		assertEquals(exp, s1);
		assertEquals(exp, s2);
	}

	@Theory
	public void test2() throws NoSuchAlgorithmException {
		ObjectId exp = ObjectId
				.fromString("84983e441c3bd26ebaae4aa1f95129e5e54670f1");

		MessageDigest m = MessageDigest.getInstance("SHA-1");
		m.update(TEST2.getBytes(UTF_8));
		ObjectId m1 = ObjectId.fromRaw(m.digest());

		SHA1 s = SHA1.newInstance();
		s.update(TEST2.getBytes(UTF_8));
		ObjectId s1 = ObjectId.fromRaw(s.digest());

		s.reset();
		s.update(TEST2.getBytes(UTF_8));
		ObjectId s2 = s.toObjectId();

		assertEquals(m1, s1);
		assertEquals(exp, s1);
		assertEquals(exp, s2);
	}

	@Theory
	public void shatteredCollision()
			throws IOException, NoSuchAlgorithmException {
		assumeFalse(
				System.getProperty("org.eclipse.jgit.util.sha1.implementation")
						.equalsIgnoreCase("jdkNative"));

		byte[] pdf1 = read("shattered-1.pdf", 422435);
		byte[] pdf2 = read("shattered-2.pdf", 422435);
		MessageDigest md;
		SHA1 s;

		// SHAttered attack generated these PDFs to have identical SHA-1.
		ObjectId bad = ObjectId
				.fromString("38762cf7f55934b34d179ae6a4c80cadccbb7f0a");
		md = MessageDigest.getInstance("SHA-1");
		md.update(pdf1);
		assertEquals("shattered-1 collides", bad,
				ObjectId.fromRaw(md.digest()));
		s = SHA1.newInstance().setDetectCollision(false);
		s.update(pdf1);
		assertEquals("shattered-1 collides", bad, s.toObjectId());

		md = MessageDigest.getInstance("SHA-1");
		md.update(pdf2);
		assertEquals("shattered-2 collides", bad,
				ObjectId.fromRaw(md.digest()));
		s = SHA1.newInstance().setDetectCollision(false);
		s.update(pdf2);
		assertEquals("shattered-2 collides", bad, s.toObjectId());

		// SHA1 with detectCollision shouldn't be fooled.
		s = SHA1.newInstance().setDetectCollision(true);
		s.update(pdf1);
		try {
			s.digest();
			fail("expected " + Sha1CollisionException.class.getSimpleName());
		} catch (Sha1CollisionException e) {
			assertEquals(e.getMessage(),
					"SHA-1 collision detected on " + bad.name());
		}

		s = SHA1.newInstance().setDetectCollision(true);
		s.update(pdf2);
		try {
			s.digest();
			fail("expected " + Sha1CollisionException.class.getSimpleName());
		} catch (Sha1CollisionException e) {
			assertEquals(e.getMessage(),
					"SHA-1 collision detected on " + bad.name());
		}
	}

	@Theory
	public void shatteredStoredInGitBlob() throws IOException {
		assumeFalse(
				System.getProperty("org.eclipse.jgit.util.sha1.implementation")
						.equalsIgnoreCase("jdkNative"));

		byte[] pdf1 = read("shattered-1.pdf", 422435);
		byte[] pdf2 = read("shattered-2.pdf", 422435);

		// Although the prior test detects the chance of a collision, adding
		// the Git blob header permutes the data enough for this specific
		// attack example to not be detected as a collision. (A different file
		// pair that takes the Git header into account however, would.)
		ObjectId id1 = blob(pdf1,
				SHA1.newInstance().setDetectCollision(true));
		ObjectId id2 = blob(pdf2,
				SHA1.newInstance().setDetectCollision(true));

		assertEquals(
				ObjectId.fromString("ba9aaa145ccd24ef760cf31c74d8f7ca1a2e47b0"),
				id1);
		assertEquals(
				ObjectId.fromString("b621eeccd5c7edac9b7dcba35a8d5afd075e24f2"),
				id2);
	}

	@Theory
	public void detectsShatteredByDefault() throws IOException {
		assumeFalse(
				System.getProperty("org.eclipse.jgit.util.sha1.implementation")
						.equalsIgnoreCase("jdkNative"));

		assumeTrue(System.getProperty("org.eclipse.jgit.util.sha1.detectCollision") == null);
		assumeTrue(System.getProperty("org.eclipse.jgit.util.sha1.safeHash") == null);

		byte[] pdf1 = read("shattered-1.pdf", 422435);
		SHA1 s = SHA1.newInstance();
		s.update(pdf1);
		try {
			s.digest();
			fail("expected " + Sha1CollisionException.class.getSimpleName());
		} catch (Sha1CollisionException e) {
			assertTrue("shattered-1 detected", true);
		}
	}

	private static ObjectId blob(byte[] pdf1, SHA1 s) {
		s.update(Constants.encodedTypeString(Constants.OBJ_BLOB));
		s.update((byte) ' ');
		s.update(Constants.encodeASCII(pdf1.length));
		s.update((byte) 0);
		s.update(pdf1);
		return s.toObjectId();
	}

	private byte[] read(String name, int sizeHint) throws IOException {
		try (InputStream in = getClass().getResourceAsStream(name)) {
			ByteBuffer buf = IO.readWholeStream(in, sizeHint);
			byte[] r = new byte[buf.remaining()];
			buf.get(r);
			return r;
		}
	}
}
