/*
 * Copyright (c) 2019, Google LLC  and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.http.server;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * Handle git-upload-pack errors.
 *
 * <p>
 * This is an entry point for customizing an error handler for git-upload-pack.
 * Right before calling {@link UploadPack#uploadWithExceptionPropagation}, JGit
 * will call this handler if specified through {@link GitFilter}. The
 * implementation of this handler is responsible for calling
 * {@link UploadPackRunnable} and handling exceptions for clients.
 *
 * <p>
 * If a custom handler is not specified, JGit will use the default error
 * handler.
 *
 * @since 5.6
 */
public interface UploadPackErrorHandler {
	/**
	 * Maps a thrown git related Exception to an appropriate HTTP status code.
	 *
	 * @param error
	 *            The thrown Exception.
	 * @return the HTTP status code as an int
	 * @since 6.1.1
	 */
	public static int statusCodeForThrowable(Throwable error) {
		if (error instanceof ServiceNotEnabledException) {
			return SC_FORBIDDEN;
		}
		if (error instanceof PackProtocolException) {
			// Internal git errors are not errors from an HTTP standpoint.
			return SC_OK;
		}
		return SC_INTERNAL_SERVER_ERROR;
	}
	/**
	 * @param req
	 *            The HTTP request
	 * @param rsp
	 *            The HTTP response
	 * @param r
	 *            A continuation that handles a git-upload-pack request.
	 * @throws IOException
	 */
	void upload(HttpServletRequest req, HttpServletResponse rsp,
			UploadPackRunnable r) throws IOException;

	/** Process a git-upload-pack request. */
	public interface UploadPackRunnable {
		/**
		 * See {@link UploadPack#uploadWithExceptionPropagation}.
		 *
		 * @throws ServiceMayNotContinueException
		 * @throws IOException
		 */
		void upload() throws ServiceMayNotContinueException, IOException;
	}
}
