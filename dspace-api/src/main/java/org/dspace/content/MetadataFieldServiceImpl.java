/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content;

import org.apache.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.MetadataFieldDAO;
import org.dspace.content.service.MetadataFieldService;
import org.dspace.content.service.MetadataValueService;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Service implementation for the MetadataField object.
 * This class is responsible for all business logic calls for the MetadataField object and is autowired by spring.
 * This class should never be accessed directly.
 *
 * @author kevinvandevelde at atmire.com
 */
public class MetadataFieldServiceImpl implements MetadataFieldService {
    /** log4j logger */
    private static Logger log = Logger.getLogger(MetadataFieldServiceImpl.class);

    @Autowired(required = true)
    protected MetadataFieldDAO metadataFieldDAO;

    @Autowired(required = true)
    protected AuthorizeService authorizeService;
    @Autowired(required = true)
    protected MetadataValueService metadataValueService;

    @Override
    public MetadataField create(Context context, MetadataSchema metadataSchema, String element, String qualifier, String scopeNote) throws AuthorizeException, SQLException, NonUniqueMetadataException {
        // Check authorisation: Only admins may create DC types
        if (!authorizeService.isAdmin(context))
        {
            throw new AuthorizeException(
                    "Only administrators may modify the metadata registry");
        }

        // Ensure the element and qualifier are unique within a given schema.
        if (hasElement(context, -1, metadataSchema, element, qualifier))
        {
            throw new NonUniqueMetadataException("Please make " + element + "."
                    + qualifier + " unique within schema #" + metadataSchema.getSchemaID());
        }

        // Create a table row and update it with the values
        MetadataField metadataField = new MetadataField();
        metadataField.setElement(element);
        metadataField.setQualifier(qualifier);
        metadataField.setScopeNote(scopeNote);
        metadataField.setMetadataSchema(metadataSchema);
        metadataField = metadataFieldDAO.create(context, metadataField);
        metadataFieldDAO.save(context, metadataField);

        log.info(LogManager.getHeader(context, "create_metadata_field",
                "metadata_field_id=" + metadataField.getFieldID()));
        return metadataField;
    }

    @Override
    public MetadataField find(Context context, int id) throws SQLException
    {
        return metadataFieldDAO.findByID(context, MetadataField.class, id);
    }

    @Override
    public MetadataField findByElement(Context context, MetadataSchema metadataSchema, String element, String qualifier) throws SQLException {
        return metadataFieldDAO.findByElement(context, metadataSchema, element, qualifier);
    }


    @Override
    public MetadataField findByElement(Context context, String metadataSchemaName, String element, String qualifier) throws SQLException {
        return metadataFieldDAO.findByElement(context, metadataSchemaName, element, qualifier);
    }

    @Override
    public List<MetadataField> findFieldsByElementNameUnqualified(Context context, String metadataSchemaName, String element) throws SQLException {
        return metadataFieldDAO.findFieldsByElementNameUnqualified(context, metadataSchemaName, element);
    }

    @Override
    public List<MetadataField> findAll(Context context) throws SQLException
    {
        return metadataFieldDAO.findAll(context, MetadataField.class);
    }

    @Override
    public List<MetadataField> findAllInSchema(Context context, MetadataSchema metadataSchema) throws SQLException {
        return metadataFieldDAO.findAllInSchema(context, metadataSchema);
    }

    @Override
    public void update(Context context, MetadataField metadataField) throws SQLException, AuthorizeException, NonUniqueMetadataException, IOException {
                // Check authorisation: Only admins may update the metadata registry
        if (!authorizeService.isAdmin(context))
        {
            throw new AuthorizeException(
                    "Only administrators may modiffy the Dublin Core registry");
        }

        // Ensure the element and qualifier are unique within a given schema.
        if (hasElement(context, metadataField.getFieldID(), metadataField.getMetadataSchema(), metadataField.getElement(), metadataField.getQualifier()))
        {
            throw new NonUniqueMetadataException("Please make " + metadataField.getMetadataSchema().getName() + "." + metadataField.getElement() + "."
                    + metadataField.getQualifier());
        }

        metadataFieldDAO.save(context, metadataField);

        log.info(LogManager.getHeader(context, "update_metadatafieldregistry",
                "metadata_field_id=" + metadataField.getFieldID() + "element=" + metadataField.getElement()
                        + "qualifier=" + metadataField.getQualifier()));
    }

    @Override
    public void delete(Context context, MetadataField metadataField) throws SQLException, AuthorizeException {
        // Check authorisation: Only admins may create DC types
        if (!authorizeService.isAdmin(context))
        {
            throw new AuthorizeException(
                    "Only administrators may modify the metadata registry");
        }

        log.info(LogManager.getHeader(context, "delete_metadata_field",
                "metadata_field_id=" + metadataField.getFieldID()));

        metadataValueService.deleteByMetadataField(context, metadataField);
        metadataFieldDAO.delete(context, metadataField);
    }

    /**
     * A sanity check that ensures a given element and qualifier are unique
     * within a given schema. The check happens in code as we cannot use a
     * database constraint.
     *
     * @param context dspace context
     * @param metadataSchema metadataSchema
     * @param element
     * @param qualifier
     * @return true if unique
     * @throws SQLException
     */
    protected boolean hasElement(Context context, int fieldId, MetadataSchema metadataSchema, String element, String qualifier) throws SQLException
    {
        return metadataFieldDAO.find(context, fieldId, metadataSchema, element, qualifier) != null;
    }

}
