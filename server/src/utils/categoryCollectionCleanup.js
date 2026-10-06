/**
 * Delete only the personal category collections owned by an account being
 * removed. Couple-scoped collections belong to the relationship history and
 * must remain available to the other member.
 */
async function cleanCategoryCollectionsForAccountDeletion(conn, userId) {
  await conn.execute("DELETE FROM item_template_preferences WHERE user_id = ?", [userId]);

  const [collections] = await conn.execute(
    `SELECT id
     FROM category_collections
     WHERE scope = 'personal' AND owner_user_id = ? AND relationship_id IS NULL
     ORDER BY id
     FOR UPDATE`,
    [userId]
  );

  for (const { id } of collections) {
    // Preferences from any legacy/other user may still reference templates in
    // this collection. Remove them before deleting the collection's templates.
    await conn.execute("DELETE FROM item_template_preferences WHERE collection_id = ?", [id]);
    await conn.execute("DELETE FROM collection_mutations WHERE collection_id = ?", [id]);
    await conn.execute("DELETE FROM collection_audits WHERE collection_id = ?", [id]);
    await conn.execute("DELETE FROM preset_bindings WHERE collection_id = ?", [id]);
    await conn.execute("DELETE FROM item_templates WHERE collection_id = ?", [id]);
    await conn.execute("DELETE FROM item_categories WHERE collection_id = ?", [id]);
    await conn.execute(
      `DELETE FROM category_collections
       WHERE id = ? AND scope = 'personal' AND owner_user_id = ? AND relationship_id IS NULL`,
      [id, userId]
    );
  }

  return collections.length;
}

/**
 * Detach shared category history before its relationship row is removed. The
 * survivor retains read-only access through archived_for_user_id; categories,
 * templates, and audits remain in place with the same collection/entity IDs.
 */
async function archiveSharedCategoryCollectionsForAccountDeletion(conn, userId, relationships) {
  let archivedCount = 0;
  for (const relationship of relationships || []) {
    const relationshipId = Number(relationship.relationship_id);
    const userId1 = Number(relationship.user_id_1);
    const userId2 = Number(relationship.user_id_2);
    const departingUserId = Number(userId);
    const survivorUserId = userId1 === departingUserId ? userId2 : userId2 === departingUserId ? userId1 : null;
    if (!Number.isSafeInteger(relationshipId) || !Number.isSafeInteger(survivorUserId) || survivorUserId <= 0) continue;

    const [collections] = await conn.execute(
      `SELECT id, domain, direction, status, version
       FROM category_collections
       WHERE scope = 'couple' AND relationship_id = ?
       ORDER BY id
       FOR UPDATE`,
      [relationshipId]
    );

    for (const collection of collections) {
      const before = {
        status: collection.status,
        relationshipId,
        memberUserIds: [userId1, userId2],
        version: Number(collection.version)
      };
      const after = {
        status: "closed",
        relationshipId: null,
        archivedForUserId: survivorUserId,
        version: Number(collection.version) + 1
      };
      const [result] = await conn.execute(
        `UPDATE category_collections
         SET status = 'closed', relationship_id = NULL, archived_for_user_id = ?, version = version + 1
         WHERE id = ? AND scope = 'couple' AND relationship_id = ?`,
        [survivorUserId, collection.id, relationshipId]
      );
      if (result.affectedRows === 0) continue;

      await conn.execute(
        `INSERT INTO collection_audits
           (collection_id, actor_user_id, action, entity_type, entity_id, before_json, after_json)
         VALUES (?, ?, 'account_delete_archive', 'collection', ?, ?, ?)`,
        [collection.id, departingUserId, collection.id, JSON.stringify(before), JSON.stringify(after)]
      );
      archivedCount += 1;
    }
  }
  return archivedCount;
}

module.exports = {
  cleanCategoryCollectionsForAccountDeletion,
  archiveSharedCategoryCollectionsForAccountDeletion
};
