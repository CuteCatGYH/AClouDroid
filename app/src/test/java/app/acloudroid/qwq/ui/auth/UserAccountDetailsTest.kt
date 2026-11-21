package app.acloudroid.qwq.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAccountDetailsTest {

    @Test
    fun testParseFromAccountInfoJson() {
        val json = """
            {
              "dsInfo": {
                "dsid": "123456789",
                "fullName": "San Zhang",
                "appleId": "zhangsan@icloud.com",
                "meCard": {
                  "photoUrl": "https://p01-contacts.icloud.com/avatar.jpg"
                }
              }
            }
        """.trimIndent()

        val details = UserAccountDetails.fromAccountInfoJson(json)
        assertEquals("123456789", details.dsid)
        assertEquals("San Zhang", details.fullName)
        assertEquals("zhangsan@icloud.com", details.appleId)
        assertEquals("https://p01-contacts.icloud.com/avatar.jpg", details.photoUrl)
        assertEquals("San Zhang", details.getDisplayName())
        assertEquals("SZ", details.getInitials())
    }

    @Test
    fun testInitialsFormatting() {
        assertEquals("张", UserAccountDetails(fullName = "张三").getInitials())
        assertEquals("李", UserAccountDetails(fullName = "李四").getInitials())
        assertEquals("SZ", UserAccountDetails(fullName = "San Zhang").getInitials())
        assertEquals("J", UserAccountDetails(fullName = "John").getInitials())
        assertEquals("A", UserAccountDetails(appleId = "admin@icloud.com").getInitials())
        assertEquals("JD", UserAccountDetails(appleId = "john.doe@icloud.com").getInitials())
        assertEquals("?", UserAccountDetails().getInitials())
    }

    @Test
    fun testFallbackFields() {
        val detailsNoFullName = UserAccountDetails.fromAccountInfoJson("""
            {
              "dsInfo": {
                "dsid": "987654321",
                "firstName": "John",
                "lastName": "Doe",
                "primaryEmail": "john.doe@icloud.com"
              }
            }
        """.trimIndent())

        assertEquals("987654321", detailsNoFullName.dsid)
        assertEquals("John Doe", detailsNoFullName.fullName)
        assertEquals("john.doe@icloud.com", detailsNoFullName.appleId)
        assertEquals("John Doe", detailsNoFullName.getDisplayName())
        assertEquals("JD", detailsNoFullName.getInitials())
    }

    @Test
    fun testInvalidJsonGracefulFallback() {
        val details = UserAccountDetails.fromAccountInfoJson("invalid json")
        assertEquals(UserAccountDetails.EMPTY, details)
        assertEquals("?", details.getInitials())
    }
}
