"""Print the long-range XMLTV fixture. Pass the emulator's Unix time as argv[1]."""
import sys
from datetime import datetime, timedelta, timezone
import xml.etree.ElementTree as ET

now = datetime.fromtimestamp(int(sys.argv[1]), timezone.utc).replace(microsecond=0)
guide = ET.Element("tv")
for channel, name in [("test1", "Тестовый канал 1"), ("test2", "Тестовый канал 2"), ("unused", "Unlisted")]:
    ET.SubElement(ET.SubElement(guide, "channel", id=channel), "display-name").text = name


def programme(channel, start, title):
    timestamp = "%Y%m%d%H%M%S %z"
    show = ET.SubElement(guide, "programme", channel=channel, start=start.strftime(timestamp),
                         stop=(start + timedelta(hours=1)).strftime(timestamp))
    ET.SubElement(show, "title").text = title
    ET.SubElement(show, "desc").text = title + " details"


programme("test1", now - timedelta(minutes=10), "Live now")
programme("test1", now + timedelta(days=7), "Week seven")
programme("test1", now + timedelta(days=21), "Marathon final")
for number in range(1, 403):
    programme("test2", now + timedelta(hours=number), f"Marathon {number:03}")
programme("unused", now + timedelta(days=25), "Marathon unlisted")
ET.ElementTree(guide).write(sys.stdout.buffer, encoding="utf-8", xml_declaration=True)
