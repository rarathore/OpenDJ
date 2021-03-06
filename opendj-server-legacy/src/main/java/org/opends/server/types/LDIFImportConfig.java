/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2009 Sun Microsystems, Inc.
 * Portions Copyright 2012-2016 ForgeRock AS.
 */
package org.opends.server.types;

import static java.lang.Boolean.*;

import static org.opends.messages.UtilityMessages.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.LocalizableMessageDescriptor.Arg1;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.forgerock.util.Pair;
import org.opends.server.tools.makeldif.MakeLDIFInputStream;
import org.opends.server.tools.makeldif.TemplateFile;
import org.opends.server.util.CollectionUtils;
import org.opends.server.util.StaticUtils;

/**
 * This class defines a data structure for holding configuration
 * information to use when performing an LDIF import.
 */
@org.opends.server.types.PublicAPI(
     stability=org.opends.server.types.StabilityLevel.VOLATILE,
     mayInstantiate=true,
     mayExtend=false,
     mayInvoke=true)
public final class LDIFImportConfig extends OperationConfig
                                    implements Closeable
{
  /** The default buffer size that will be used when reading LDIF data. */
  private static final int DEFAULT_BUFFER_SIZE = 8192;

  /**
   * Indicates whether to include the objectclasses in the entries
   * read from the import.
   */
  private boolean includeObjectClasses = true;

  /** Indicates whether to invoke LDIF import plugins whenever an entry is read. */
  private boolean invokeImportPlugins;
  /** Indicates whether the import is compressed. */
  private boolean isCompressed;
  /** Indicates whether the import is encrypted. */
  private boolean isEncrypted;
  /** Indicates whether to clear all base DNs in a backend. */
  private boolean clearBackend;
  /** Indicates whether to perform schema validation on the entries read. */
  private boolean validateSchema = true;

  /** The buffered reader from which the LDIF data should be read. */
  private BufferedReader reader;
  /** The buffered writer to which rejected entries should be written. */
  private BufferedWriter rejectWriter;
  /** The buffered writer to which rejected entries should be written. */
  private BufferedWriter skipWriter;
  /** The input stream to use to read the data to import. */
  private InputStream ldifInputStream;

  /** The buffer size to use when reading data from the LDIF file. */
  private int bufferSize = DEFAULT_BUFFER_SIZE;

  /** The iterator used to read through the set of LDIF files. */
  private final Iterator<String> ldifFileIterator;

  /** The set of base DNs to exclude from the import. */
  private Set<DN> excludeBranches = new HashSet<>(0);
  /** The set of base DNs to include from the import. */
  private Set<DN> includeBranches = new HashSet<>(0);

  /** The set of search filters for entries to exclude from the import. */
  private List<SearchFilter> excludeFilters = new ArrayList<>(0);
  /** The set of search filters for entries to include in the import. */
  private List<SearchFilter> includeFilters = new ArrayList<>(0);

  /** The set of LDIF files to be imported. */
  private final List<String> ldifFiles;

  /** The set of attribute types that should be excluded from the import. */
  private Set<AttributeType> excludeAttributes = new HashSet<>(0);
  /** The set of attribute types that should be included in the import. */
  private Set<AttributeType> includeAttributes = new HashSet<>(0);

  /** Indicates whether all the user attributes should be included. */
  private boolean includeAllUserAttrs;
  /** Indicates whether all the operational attributes should be included. */
  private boolean includeAllOpAttrs;
  /** Indicates whether all the user attributes should be excluded. */
  private boolean excludeAllUserAttrs;
  /** Indicates whether all the operational attributes should be excluded. */
  private boolean excludeAllOpAttrs;

  private String tmpDirectory;
  private int threadCount;

  /**
   * Creates a new LDIF import configuration that will read from the
   * specified LDIF file.
   *
   * @param  ldifFile  The path to the LDIF file with the data to
   *                   import.
   */
  public LDIFImportConfig(String ldifFile)
  {
    ldifFiles = CollectionUtils.newArrayList(ldifFile);
    ldifFileIterator = ldifFiles.iterator();
  }

  /**
   * Creates a new LDIF import configuration that will read from the
   * specified LDIF files.  The files will be imported in the order
   * they are specified in the provided list.
   *
   * @param  ldifFiles  The paths to the LDIF files with the data to
   *                    import.
   */
  public LDIFImportConfig(List<String> ldifFiles)
  {
    this.ldifFiles = ldifFiles;
    ldifFileIterator = ldifFiles.iterator();
  }

  /**
   * Creates a new LDIF import configuration that will read from the
   * provided input stream.
   *
   * @param  ldifInputStream  The input stream from which to read the
   *                          LDIF data.
   */
  public LDIFImportConfig(InputStream ldifInputStream)
  {
    this(Collections.<String> emptyList());
    this.ldifInputStream   = ldifInputStream;
  }

  /**
   * Creates a new LDIF import configuration that will read from the
   * provided reader.
   *
   * @param  ldifInputReader  The input stream from which to read the
   *                          LDIF data.
   */
  public LDIFImportConfig(Reader ldifInputReader)
  {
    this(Collections.<String> emptyList());
    reader                 = getBufferedReader(ldifInputReader);
  }

  /**
   * Wrap reader in a BufferedReader if necessary.
   *
   * @param reader the reader to buffer
   * @return reader as a BufferedReader
   */
  private BufferedReader getBufferedReader(Reader reader) {
    if (reader instanceof BufferedReader) {
      return (BufferedReader)reader;
    }
    return new BufferedReader(reader);
  }

  /**
   * Creates a new LDIF import configuration that will generate
   * entries using the given MakeLDIF template file rather than
   * reading them from an existing LDIF file.
   *
   * @param  templateFile  The template file to use to generate the
   *                       entries.
   */
  public LDIFImportConfig(TemplateFile templateFile)
  {
    this(new MakeLDIFInputStream(templateFile));
  }



  /**
   * Retrieves the reader that should be used to read the LDIF data.
   * Note that if the LDIF file is compressed and/or encrypted, then
   * that must be indicated before this method is called for the first
   * time.
   *
   * @return  The reader that should be used to read the LDIF data.
   *
   * @throws  IOException  If a problem occurs while obtaining the
   *                       reader.
   */
  public BufferedReader getReader()
         throws IOException
  {
    if (reader == null)
    {
      InputStream inputStream;
      if (ldifInputStream != null)
      {
        inputStream = ldifInputStream;
      }
      else
      {
        inputStream = ldifInputStream =
             new FileInputStream(ldifFileIterator.next());
      }

      if (isEncrypted)
      {
        // FIXME -- Add support for encryption with a cipher input
        //          stream.
      }

      if (isCompressed)
      {
        inputStream = new GZIPInputStream(inputStream);
      }

      reader = new BufferedReader(new InputStreamReader(inputStream),
                                  bufferSize);
    }

    return reader;
  }



  /**
   * Retrieves the LDIF reader configured to read from the next LDIF
   * file in the list.
   *
   * @return  The reader that should be used to read the LDIF data, or
   *          <CODE>null</CODE> if there are no more files to read.
   *
   * @throws  IOException  If a problem occurs while obtaining the reader.
   */
  public BufferedReader nextReader()
         throws IOException
  {
    if (ldifFileIterator == null || !ldifFileIterator.hasNext())
    {
      return null;
    }

    reader.close();

    InputStream inputStream = ldifInputStream =
         new FileInputStream(ldifFileIterator.next());

    if (isEncrypted)
    {
      // FIXME -- Add support for encryption with a cipher input stream.
    }

    if (isCompressed)
    {
      inputStream = new GZIPInputStream(inputStream);
    }

    reader = new BufferedReader(new InputStreamReader(inputStream), bufferSize);
    return reader;
  }



  /**
   * Retrieves the writer that should be used to write entries that
   * are rejected rather than imported for some reason.
   *
   * @return  The reject writer, or <CODE>null</CODE> if none is to be used.
   */
  public BufferedWriter getRejectWriter()
  {
    return rejectWriter;
  }

  /**
   * Retrieves the writer that should be used to write entries that
   * are skipped because they don't match the criteria.
   *
   * @return  The skip writer, or <CODE>null</CODE> if none is to be used.
   */
  public BufferedWriter getSkipWriter()
  {
    return skipWriter;
  }

  /**
   * Indicates that rejected entries should be written to the
   * specified file.  Note that this applies only to entries that are
   * rejected because they are invalid (e.g., are malformed or don't
   * conform to schema requirements), and not to entries that are
   * rejected because they matched exclude criteria.
   *
   * @param  rejectFile            The path to the file to which
   *                               reject information should be written.
   * @param  existingFileBehavior  Indicates how to treat an existing file.
   *
   * @throws  IOException  If a problem occurs while opening the
   *                       reject file for writing.
   */
  public void writeRejectedEntries(String rejectFile,
                   ExistingFileBehavior existingFileBehavior)
         throws IOException
  {
    if (rejectFile == null)
    {
      closeRejectWriter();
      return;
    }

    final BufferedWriter writer = newBufferedWriter(rejectFile, existingFileBehavior, ERR_REJECT_FILE_EXISTS);
    if (writer != null)
    {
      rejectWriter = writer;
    }
  }

  private BufferedWriter newBufferedWriter(String file, ExistingFileBehavior existingFileBehavior,
      Arg1<Object> fileExistsErrorMsg) throws IOException
  {
    switch (existingFileBehavior)
    {
    case APPEND:
      return new BufferedWriter(new FileWriter(file, true));
    case OVERWRITE:
      return new BufferedWriter(new FileWriter(file, false));
    case FAIL:
      File f = new File(file);
      if (f.exists())
      {
        throw new IOException(fileExistsErrorMsg.get(file).toString());
      }
      return new BufferedWriter(new FileWriter(file));
    default:
      return null;
    }
  }

  /**
   * Indicates that rejected entries should be written to the provided
   * output stream.  Note that this applies only to entries that are
   * rejected because they are invalid (e.g., are malformed or don't
   * conform to schema requirements), and not to entries that are
   * rejected because they matched exclude criteria.
   *
   * @param  outputStream  The output stream to which rejected entries
   *                       should be written.
   */
  public void writeRejectedEntries(OutputStream outputStream)
  {
    if (outputStream == null)
    {
      closeRejectWriter();
      return;
    }

    rejectWriter =
         new BufferedWriter(new OutputStreamWriter(outputStream));
  }

  private void closeRejectWriter()
  {
    if (rejectWriter != null)
    {
      StaticUtils.close(rejectWriter);
      rejectWriter = null;
    }
  }

  /**
   * Indicates that skipped entries should be written to the
   * specified file.  Note that this applies only to entries that are
   * skipped because they matched exclude criteria.
   *
   * @param  skipFile              The path to the file to which
   *                               skipped information should be written.
   * @param  existingFileBehavior  Indicates how to treat an existing file.
   *
   * @throws  IOException  If a problem occurs while opening the
   *                       skip file for writing.
   */
  public void writeSkippedEntries(String skipFile,
                   ExistingFileBehavior existingFileBehavior)
         throws IOException
  {
    if (skipFile == null)
    {
      closeSkipWriter();
      return;
    }

    final BufferedWriter writer = newBufferedWriter(skipFile, existingFileBehavior, ERR_SKIP_FILE_EXISTS);
    if (writer != null)
    {
      skipWriter = writer;
    }
  }

  private void closeSkipWriter()
  {
    if (skipWriter != null)
    {
      StaticUtils.close(skipWriter);
      skipWriter = null;
    }
  }





  /**
   * Indicates whether any LDIF import plugins registered with the
   * server should be invoked during the import operation.
   *
   * @return  <CODE>true</CODE> if registered LDIF import plugins
   *          should be invoked during the import operation, or
   *          <CODE>false</CODE> if they should not be invoked.
   */
  public boolean invokeImportPlugins()
  {
    return invokeImportPlugins;
  }



  /**
   * Specifies whether any LDIF import plugins registered with the
   * server should be invoked during the import operation.
   *
   * @param  invokeImportPlugins  Specifies whether any LDIF import
   *                              plugins registered with the server
   *                              should be invoked during the import
   *                              operation.
   */
  public void setInvokeImportPlugins(boolean invokeImportPlugins)
  {
    this.invokeImportPlugins = invokeImportPlugins;
  }



  /**
   * Indicates whether the input LDIF source is expected to be
   * compressed.
   *
   * @return  <CODE>true</CODE> if the LDIF source is expected to be
   *          compressed, or <CODE>false</CODE> if not.
   */
  public boolean isCompressed()
  {
    return isCompressed;
  }



  /**
   * Specifies whether the input LDIF source is expected to be
   * compressed.  If compression is used, then this must be set prior
   * to the initial call to <CODE>getReader</CODE>.
   *
   * @param  isCompressed  Indicates whether the input LDIF source is
   *                       expected to be compressed.
   */
  public void setCompressed(boolean isCompressed)
  {
    this.isCompressed = isCompressed;
  }



  /**
   * Indicates whether the input LDIF source is expected to be
   * encrypted.
   *
   * @return  <CODE>true</CODE> if the LDIF source is expected to be
   *          encrypted, or <CODE>false</CODE> if not.
   */
  public boolean isEncrypted()
  {
    return isEncrypted;
  }



  /**
   * Specifies whether the input LDIF source is expected to be
   * encrypted.  If encryption is used, then this must be set prior to
   * the initial call to <CODE>getReader</CODE>.
   *
   * @param  isEncrypted  Indicates whether the input LDIF source is
   *                      expected to be encrypted.
   */
  public void setEncrypted(boolean isEncrypted)
  {
    this.isEncrypted = isEncrypted;
  }



  /**
   * Indicates whether to clear the entire backend if importing to a
   * backend with more than one base DNs.
   *
   * @return <CODE>true</code> if the entire backend should be
   * cleared or <CODE>false</CODE> if not.
   */
  public boolean clearBackend()
  {
    return clearBackend;
  }



  /**
   * Specifies whether to clear the entire backend if importing to a
   * backend.
   *
   * @param clearBackend Indicates whether to clear the entire
   * backend.
   */
  public void setClearBackend(boolean clearBackend)
  {
    this.clearBackend = clearBackend;
  }



  /**
   * Indicates whether to perform schema validation on entries as they
   * are read.
   *
   * @return  <CODE>true</CODE> if schema validation should be
   *          performed on the entries as they are read, or
   *          <CODE>false</CODE> if not.
   */
  public boolean validateSchema()
  {
    return validateSchema;
  }



  /**
   * Specifies whether to perform schema validation on entries as they
   * are read.
   *
   * @param  validateSchema  Indicates whether to perform schema
   *                         validation on entries as they are read.
   */
  public void setValidateSchema(boolean validateSchema)
  {
    this.validateSchema = validateSchema;
  }



  /**
   * Retrieves the set of base DNs that specify the set of entries to
   * exclude from the import.  The contents of the returned list may
   * be altered by the caller.
   *
   * @return  The set of base DNs that specify the set of entries to
   *          exclude from the import.
   */
  public Set<DN> getExcludeBranches()
  {
    return excludeBranches;
  }



  /**
   * Specifies the set of base DNs that specify the set of entries to
   * exclude from the import.
   *
   * @param  excludeBranches  The set of base DNs that specify the set
   *                          of entries to exclude from the import.
   */
  public void setExcludeBranches(Set<DN> excludeBranches)
  {
    this.excludeBranches = getSet(excludeBranches);
  }

  private <T> Set<T> getSet(Set<T> set)
  {
    return set != null ? set : new HashSet<T>(0);
  }


  /**
   * Retrieves the set of base DNs that specify the set of entries to
   * include in the import.  The contents of the returned list may be
   * altered by the caller.
   *
   * @return  The set of base DNs that specify the set of entries to
   *          include in the import.
   */
  public Set<DN> getIncludeBranches()
  {
    return includeBranches;
  }



  /**
   * Specifies the set of base DNs that specify the set of entries to
   * include in the import.
   *
   * @param  includeBranches  The set of base DNs that specify the set
   *                          of entries to include in the import.
   */
  public void setIncludeBranches(Set<DN> includeBranches)
  {
    this.includeBranches = getSet(includeBranches);
  }

  /**
   * Indicates whether to include the entry with the specified DN in the import.
   *
   * @param dn
   *          The DN of the entry for which to make the determination.
   * @return a pair where the first element is a boolean indicating whether the entry with the
   *         specified DN should be included in the import, and the second element is a message with
   *         the reason why an entry is not included in the import (it is {@code null} when the
   *         entry is included in the import).
   */
  public Pair<Boolean, LocalizableMessage> includeEntry(DN dn)
  {
    if (! excludeBranches.isEmpty())
    {
      for (DN excludeBranch : excludeBranches)
      {
        if (excludeBranch.isSuperiorOrEqualTo(dn))
        {
          return Pair.of(FALSE, ERR_LDIF_SKIP_EXCLUDE_BRANCH.get(dn, excludeBranch));
        }
      }
    }

    if (! includeBranches.isEmpty())
    {
      for (DN includeBranch : includeBranches)
      {
        if (includeBranch.isSuperiorOrEqualTo(dn))
        {
          return Pair.of(TRUE, null);
        }
      }

      return Pair.of(FALSE, ERR_LDIF_SKIP_NOT_IN_INCLUDED_BRANCHES.get(dn));
    }

    return Pair.of(TRUE, null);
  }



  /**
   * Indicates whether the set of objectclasses should be included in
   * the entries read from the LDIF.
   *
   * @return  <CODE>true</CODE> if the set of objectclasses should be
   *          included in the entries read from the LDIF, or
   *          <CODE>false</CODE> if not.
   */
  public boolean includeObjectClasses()
  {
    return includeObjectClasses;
  }



  /**
   * Specifies whether the set of objectclasses should be included in
   * the entries read from the LDIF.
   *
   * @param  includeObjectClasses  Indicates whether the set of
   *                               objectclasses should be included in
   *                               the entries read from the LDIF.
   */
  public void setIncludeObjectClasses(boolean includeObjectClasses)
  {
    this.includeObjectClasses = includeObjectClasses;
  }



  /**
   * Retrieves the set of attributes that should be excluded from the
   * entries read from the LDIF.  The contents of the returned set may
   * be modified by the caller.
   *
   * @return  The set of attributes that should be excluded from the
   *          entries read from the LDIF.
   */
  public Set<AttributeType> getExcludeAttributes()
  {
    return excludeAttributes;
  }



  /**
   * Specifies the set of attributes that should be excluded from the
   * entries read from the LDIF.
   *
   * @param  excludeAttributes  The set of attributes that should be
   *                            excluded from the entries read from
   *                            the LDIF.
   */
  public void setExcludeAttributes(Set<AttributeType> excludeAttributes)
  {
    this.excludeAttributes = getSet(excludeAttributes);
  }

  /**
   * Retrieves the set of attributes that should be included in the
   * entries read from the LDIF.  The contents of the returned set may
   * be modified by the caller.
   *
   * @return  The set of attributes that should be included in the
   *          entries read from the LDIF.
   */
  public Set<AttributeType> getIncludeAttributes()
  {
    return includeAttributes;
  }



  /**
   * Specifies the set of attributes that should be included in the
   * entries read from the LDIF.
   *
   * @param  includeAttributes  The set of attributes that should be
   *                            included in the entries read from the
   *                            LDIF.
   */
  public void setIncludeAttributes(Set<AttributeType> includeAttributes)
  {
    this.includeAttributes = getSet(includeAttributes);
  }

  /**
   * Indicates whether the specified attribute should be included in
   * the entries read from the LDIF.
   *
   * @param  attributeType  The attribute type for which to make the
   *                        determination.
   *
   * @return  <CODE>true</CODE> if the specified attribute should be
   *          included in the entries read from the LDIF, or
   *         <CODE>false</CODE> if not.
   */
  public boolean includeAttribute(AttributeType attributeType)
  {
    if (!excludeAttributes.isEmpty()
        && excludeAttributes.contains(attributeType))
    {
      return false;
    }

     if((excludeAllOpAttrs && attributeType.isOperational())
         || (excludeAllUserAttrs && !attributeType.isOperational()))
    {
      return false;
    }

    if((includeAllUserAttrs && !attributeType.isOperational())
        || (includeAllOpAttrs && attributeType.isOperational()))
    {
      return true;
    }

    if (! includeAttributes.isEmpty())
    {
      return includeAttributes.contains(attributeType);
    }
    else if((includeAllUserAttrs && attributeType.isOperational())
        || (includeAllOpAttrs && !attributeType.isOperational()))
    {
      return false;
    }
    return true;
  }



  /**
   * Retrieves the set of search filters that should be used to
   * determine which entries to exclude from the LDIF.  The contents
   * of the returned list may be modified by the caller.
   *
   * @return  The set of search filters that should be used to
   *          determine which entries to exclude from the LDIF.
   */
  public List<SearchFilter> getExcludeFilters()
  {
    return excludeFilters;
  }



  /**
   * Specifies the set of search filters that should be used to
   * determine which entries to exclude from the LDIF.
   *
   * @param  excludeFilters  The set of search filters that should be
   *                         used to determine which entries to
   *                         exclude from the LDIF.
   */
  public void setExcludeFilters(List<SearchFilter> excludeFilters)
  {
    this.excludeFilters = getList(excludeFilters);
  }

  /**
   * Retrieves the set of search filters that should be used to
   * determine which entries to include in the LDIF.  The contents of
   * the returned list may be modified by  the caller.
   *
   * @return  The set of search filters that should be used to
   *          determine which entries to include in the LDIF.
   */
  public List<SearchFilter> getIncludeFilters()
  {
    return includeFilters;
  }



  /**
   * Specifies the set of search filters that should be used to
   * determine which entries to include in the LDIF.
   *
   * @param  includeFilters  The set of search filters that should be
   *                         used to determine which entries to
   *                         include in the LDIF.
   */
  public void setIncludeFilters(List<SearchFilter> includeFilters)
  {
    this.includeFilters = getList(includeFilters);
  }

  private <T> List<T> getList(List<T> list)
  {
    return list != null ? list : new ArrayList<T>(0);
  }

  /**
   * Indicates whether the specified entry should be included in the
   * import based on the configured set of include and exclude filters.
   *
   * @param  entry  The entry for which to make the determination.
   * @return  a pair where the first element is a boolean indicating whether
   *          the entry with the specified DN should be included in the import,
   *          and the second element is a message with the reason why an entry
   *          is not included in the import (it is {@code null} when the entry
   *          is included in the import).
   * @throws  DirectoryException  If there is a problem with any of
   *                              the search filters used to make the
   *                              determination.
   */
  public Pair<Boolean, LocalizableMessage> includeEntry(Entry entry) throws DirectoryException
  {
    if (! excludeFilters.isEmpty())
    {
      for (SearchFilter excludeFilter : excludeFilters)
      {
        if (excludeFilter.matchesEntry(entry))
        {
          return Pair.of(FALSE, ERR_LDIF_SKIP_EXCLUDE_FILTER.get(entry.getName(), excludeFilter));
        }
      }
    }

    if (! includeFilters.isEmpty())
    {
      for (SearchFilter includeFilter : includeFilters)
      {
        if (includeFilter.matchesEntry(entry))
        {
          return Pair.of(TRUE, null);
        }
      }

      return Pair.of(FALSE, ERR_LDIF_SKIP_NOT_IN_INCLUDED_FILTERS.get(entry.getName()));
    }

    return Pair.of(TRUE, null);
  }



  /**
   * Retrieves the buffer size that should be used when reading LDIF
   * data.
   *
   * @return  The buffer size that should be used when reading LDIF
   *          data.
   */
  public int getBufferSize()
  {
    return bufferSize;
  }



  /**
   * Specifies the buffer size that should be used when reading LDIF
   * data.
   *
   * @param  bufferSize  The buffer size that should be used when
   *                     reading LDIF data.
   */
  public void setBufferSize(int bufferSize)
  {
    this.bufferSize = bufferSize;
  }



    /**
   * Specifies whether all the user attributes should be excluded.
   *
   * @param  excludeAllUserAttrs  Specifies all user attributes to
   *         be excluded.
   */
  public void setExcludeAllUserAttributes(boolean excludeAllUserAttrs)
  {
    this.excludeAllUserAttrs = excludeAllUserAttrs;
  }



  /**
   * Specifies whether all the operational attributes should be
   * excluded.
   *
   * @param  excludeAllOpAttrs  Specifies whether all the
   *                            operational attributes
   *                            should be excluded.
   */
  public void setExcludeAllOperationalAttributes(boolean excludeAllOpAttrs)
  {
    this.excludeAllOpAttrs = excludeAllOpAttrs;
  }



  /**
   * Specifies whether all the operational attributes should be
   * included.
   *
   * @param  includeAllOpAttrs  Specifies whether all
   *         the operation attributes should be included.
   *
   */
  public void setIncludeAllOpAttributes(boolean includeAllOpAttrs)
  {
    this.includeAllOpAttrs = includeAllOpAttrs;
  }



  /**
   * Specifies whether all the user attributes should be included.
   *
   * @param  includeAllUserAttrs  Specifies whether all the
   *                              user attributes should be
   *                              included.
   */
  public void setIncludeAllUserAttributes(boolean includeAllUserAttrs)
  {
    this.includeAllUserAttrs = includeAllUserAttrs;
  }



  /** Closes any resources that this import config might have open. */
  @Override
  public void close()
  {
    StaticUtils.close(reader, rejectWriter, skipWriter);
  }

  /**
   * Set the temporary directory to the specified path.
   *
   * @param path The path to set the temporary directory to.
   */
  public void setTmpDirectory(String path)
  {
    tmpDirectory = path;
  }

  /**
   * Return the temporary directory path.
   *
   * @return  The temporary directory string.
   */
  public String getTmpDirectory()
  {
    return tmpDirectory;
  }

  /**
   * Set the thread count.
   *
   * @param c The thread count value.
   */
  public void setThreadCount(int c)
  {
    this.threadCount = c;
  }

  /**
   * Return the specified thread count.
   *
   * @return The thread count.
   */
  public int getThreadCount()
  {
    return this.threadCount;
  }
}
